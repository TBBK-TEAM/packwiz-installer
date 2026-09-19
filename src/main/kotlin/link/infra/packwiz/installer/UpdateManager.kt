package link.infra.packwiz.installer

import cc.ekblad.toml.decode
import com.google.gson.GsonBuilder
import com.google.gson.JsonIOException
import com.google.gson.JsonSyntaxException
import link.infra.packwiz.installer.DownloadTask.Companion.createTasksFromIndex
import link.infra.packwiz.installer.metadata.DownloadMode
import link.infra.packwiz.installer.metadata.IndexFile
import link.infra.packwiz.installer.metadata.ManifestFile
import link.infra.packwiz.installer.metadata.PackFile
import link.infra.packwiz.installer.metadata.curseforge.resolveCfMetadata
import link.infra.packwiz.installer.metadata.hash.Hash
import link.infra.packwiz.installer.metadata.hash.HashFormat
import link.infra.packwiz.installer.request.RequestException
import link.infra.packwiz.installer.target.ClientHolder
import link.infra.packwiz.installer.target.Side
import link.infra.packwiz.installer.target.path.PackwizFilePath
import link.infra.packwiz.installer.target.path.PackwizPath
import link.infra.packwiz.installer.ui.IUserInterface
import link.infra.packwiz.installer.ui.IUserInterface.CancellationResult
import link.infra.packwiz.installer.ui.IUserInterface.ExceptionListResult
import link.infra.packwiz.installer.ui.data.InstallProgress
import link.infra.packwiz.installer.util.Log
import okio.buffer
import java.io.IOException
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletionService
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import kotlin.system.exitProcess

class UpdateManager internal constructor(private val opts: Options, val ui: IUserInterface) {
	private var cancelled = false
	private var cancelledStartGame = false
	private var errorsOccurred = false

	init {
		start()
	}

	data class Options(
		val packFile: PackwizPath<*>,
		val manifestFile: PackwizFilePath,
		val packFolder: PackwizFilePath,
		val multimcFolder: PackwizFilePath,
		val side: Side,
		val timeout: Long,
		// Delete files that are no longer part of the pack (removed mods, configs, resource packs, ...)
		val prune: Boolean = true,
		// Also delete files that the pack doesn't manage, inside folders the pack installs files into
		val pruneAll: Boolean = false,
		// Like pruneAll, but also cleans up folders the pack used to manage, and empty folders, so
		// that the pack folder cannot drift out of sync with the pack
		val fullSync: Boolean = false,
	)

	// TODO: make this return a value based on results?
	private fun start() {
		val clientHolder = ClientHolder()
		ui.cancelCallback = {
			clientHolder.close()
		}

		ui.submitProgress(InstallProgress("Loading manifest file..."))
		val gson = GsonBuilder()
			.registerTypeAdapter(Hash::class.java, Hash.TypeHandler())
			.registerTypeAdapter(PackwizFilePath::class.java, PackwizPath.adapterRelativeTo(opts.packFolder))
			.enableComplexMapKeySerialization()
			.create()
		val manifest = try {
			// TODO: kotlinx.serialisation?
			InputStreamReader(opts.manifestFile.source(clientHolder).inputStream(), StandardCharsets.UTF_8).use { reader ->
				gson.fromJson(reader, ManifestFile::class.java)
			}
		} catch (e: RequestException.Response.File.FileNotFound) {
			ui.firstInstall = true
			ManifestFile()
		} catch (e: JsonSyntaxException) {
			ui.showErrorAndExit("Invalid local manifest file, try deleting ${opts.manifestFile}", e)
		} catch (e: JsonIOException) {
			ui.showErrorAndExit("Failed to read local manifest file, try deleting ${opts.manifestFile}", e)
		}

		if (ui.cancelButtonPressed) {
			showCancellationDialog()
			handleCancellation()
		}

		ui.submitProgress(InstallProgress("Loading pack file..."))
		val packFileSource = try {
			val src = opts.packFile.source(clientHolder)
			HashFormat.SHA256.source(src)
		} catch (e: Exception) {
			// TODO: ensure suppressed/caused exceptions are shown?
			ui.showErrorAndExit("Failed to download pack.toml", e)
		}
		val pf = packFileSource.buffer().use {
			try {
				PackFile.mapper(opts.packFile).decode<PackFile>(it.inputStream())
			} catch (e: IllegalStateException) {
				ui.showErrorAndExit("Failed to parse pack.toml", e)
			}
		}

		if (ui.cancelButtonPressed) {
			showCancellationDialog()
			handleCancellation()
		}

		// Launcher checks
		val lu = LauncherUtils(opts, ui)

		// MultiMC MC and loader version checker
		ui.submitProgress(InstallProgress("Loading MultiMC pack file..."))
		try {
			when (lu.handleMultiMC(pf, gson)) {
				LauncherUtils.LauncherStatus.CANCELLED -> cancelled = true
				LauncherUtils.LauncherStatus.NOT_FOUND -> Log.info("MultiMC not detected")
				else -> {}
			}
			handleCancellation()
		} catch (e: Exception) {
			ui.showErrorAndExit(e.message!!, e)
		}

		if (ui.cancelButtonPressed) {
			showCancellationDialog()
			handleCancellation()
		}

		ui.submitProgress(InstallProgress("Checking local files..."))

		// If the side changes, invalidate EVERYTHING (even when the index hasn't changed)
		val invalidateAll = opts.side != manifest.cachedSide
		val invalidatedUris: MutableList<PackwizFilePath> = ArrayList()
		if (!invalidateAll) {
			// Invalidation checking must be done here, as it must happen before pack/index hashes are checked
			for ((fileUri, file) in manifest.cachedFiles) {
				// ignore onlyOtherSide files
				if (file.onlyOtherSide) {
					continue
				}

				var invalid = false
				// if isn't optional, or is optional but optionValue == true
				if (!file.isOptional || file.optionValue) {
					if (file.cachedLocation != null) {
						if (!file.cachedLocation!!.nioPath.toFile().exists()) {
							invalid = true
						}
					} else {
						// if cachedLocation == null, should probably be installed!!
						invalid = true
					}
				}
				if (invalid) {
					Log.info("File ${fileUri.filename} invalidated, marked for redownloading")
					invalidatedUris.add(fileUri)
				}
			}

			if (manifest.packFileHash?.let { it == packFileSource.hash } == true && invalidatedUris.isEmpty()) {
				// todo: --force?
				ui.submitProgress(InstallProgress("Modpack is already up to date!", 1, 1))
				if (manifest.cachedFiles.any { it.value.isOptional }) {
					ui.awaitOptionalButton(false, opts.timeout)
				}
				if (!ui.optionsButtonPressed) {
					return
				}
			}
		}

		Log.info("Modpack name: ${pf.name}")

		if (ui.cancelButtonPressed) {
			showCancellationDialog()
			handleCancellation()
		}
		try {
			processIndex(
				pf.index.file,
				pf.index.hashFormat.fromString(pf.index.hash),
				pf.index.hashFormat,
				manifest,
				invalidatedUris,
				invalidateAll,
				clientHolder
			)
		} catch (e1: Exception) {
			ui.showErrorAndExit("Failed to process index file", e1)
		}

		handleCancellation()


		// If there were errors, don't write the manifest/index hashes, to ensure they are rechecked later
		if (errorsOccurred) {
			manifest.indexFileHash = null
			manifest.packFileHash = null
		} else {
			manifest.packFileHash = packFileSource.hash
		}

		manifest.cachedSide = opts.side
		try {
			Files.newBufferedWriter(opts.manifestFile.nioPath, StandardCharsets.UTF_8).use { writer -> gson.toJson(manifest, writer) }
		} catch (e: IOException) {
			ui.showErrorAndExit("Failed to save local manifest file", e)
		}
	}

	private fun processIndex(indexUri: PackwizPath<*>, indexHash: Hash<*>, hashFormat: HashFormat<*>, manifest: ManifestFile, invalidatedFiles: List<PackwizFilePath>, invalidateAll: Boolean, clientHolder: ClientHolder) {
		if (!invalidateAll) {
			if (manifest.indexFileHash == indexHash && invalidatedFiles.isEmpty()) {
				ui.submitProgress(InstallProgress("Modpack files are already up to date!", 1, 1))
				if (manifest.cachedFiles.any { it.value.isOptional }) {
					ui.awaitOptionalButton(false, opts.timeout)
				}
				if (!ui.optionsButtonPressed) {
					return
				}
				if (ui.cancelButtonPressed) {
					showCancellationDialog()
					return
				}
			}
		}
		manifest.indexFileHash = indexHash

		val indexFileSource = try {
			val src = indexUri.source(clientHolder)
			hashFormat.source(src)
		} catch (e: Exception) {
			ui.showErrorAndExit("Failed to download index file", e)
		}

		val indexFile = try {
			IndexFile.mapper(indexUri).decode<IndexFile>(indexFileSource.buffer().inputStream())
		} catch (e: IllegalStateException) {
			ui.showErrorAndExit("Failed to parse index file", e)
		}
		if (indexHash != indexFileSource.hash) {
			ui.showErrorAndExit("Your index file hash is invalid! The pack developer should packwiz refresh on the pack again")
		}

		if (ui.cancelButtonPressed) {
			showCancellationDialog()
			return
		}

		ui.submitProgress(InstallProgress("Checking local files..."))
		if (opts.prune) {
			deleteFilesRemovedFromPack(indexFile, manifest)
		}

		if (ui.cancelButtonPressed) {
			showCancellationDialog()
			return
		}
		ui.submitProgress(InstallProgress("Comparing new files..."))

		// TODO: progress bar?
		if (indexFile.files.isEmpty()) {
			Log.warn("Index is empty!")
		}
		val tasks = createTasksFromIndex(indexFile, opts.side)
		if (invalidateAll) {
			Log.info("Side changed, invalidating all mods")
		}
		tasks.forEach{ f ->
			// TODO: should linkedfile be checked as well? should this be done in the download section?
			if (invalidateAll) {
				f.invalidate()
			} else if (invalidatedFiles.contains(f.metadata.file.rebase(opts.packFolder))) {
				f.invalidate()
			}
			val file = manifest.cachedFiles[f.metadata.file.rebase(opts.packFolder)]
			// Ensure the file can be reverted later if necessary - the DownloadTask modifies the file so if it fails we need the old version back
			file?.backup()
			// If it is null, the DownloadTask will make a new empty cachedFile
			f.updateFromCache(file)
		}

		if (ui.cancelButtonPressed) {
			showCancellationDialog()
			return
		}

		// Let's hope downloadMetadata is a pure function!!!
		tasks.parallelStream().forEach { f -> f.downloadMetadata(clientHolder) }

		val failedTaskDetails = tasks.asSequence().map(DownloadTask::exceptionDetails).filterNotNull().toList()
		if (failedTaskDetails.isNotEmpty()) {
			errorsOccurred = true
			when (ui.showExceptions(failedTaskDetails, tasks.size, true)) {
				ExceptionListResult.CONTINUE -> {}
				ExceptionListResult.CANCEL -> {
					cancelled = true
					return
				}
				ExceptionListResult.IGNORE -> {
					cancelledStartGame = true
					return
				}
			}
		}

		if (ui.cancelButtonPressed) {
			showCancellationDialog()
			return
		}

		// TODO: task failed function?
		tasks.removeAll { it.failed() }
		val optionTasks = tasks.filter(DownloadTask::correctSide).filter(DownloadTask::isOptional).toList()
		val optionsChanged = optionTasks.any(DownloadTask::isNewOptional)
		if (optionTasks.isNotEmpty() && !optionsChanged) {
			if (!ui.optionsButtonPressed) {
				// TODO: this is so ugly
				ui.submitProgress(InstallProgress("Reconfigure optional mods?", 0,1))
				ui.awaitOptionalButton(true, opts.timeout)
				if (ui.cancelButtonPressed) {
					showCancellationDialog()
					return
				}
			}
		}
		// If options changed, present all options again
		if (ui.optionsButtonPressed || optionsChanged) {
			// new ArrayList is required so it's an IOptionDetails rather than a DownloadTask list
			if (ui.showOptions(ArrayList(optionTasks))) {
				cancelled = true
				handleCancellation()
			}
		}
		// TODO: keep this enabled? then apply changes after download process?
		ui.disableOptionsButton(optionTasks.isNotEmpty())

		while (true) {
			when (validateAndResolve(tasks, clientHolder)) {
				ResolveResult.RETRY -> {}
				ResolveResult.QUIT -> return
				ResolveResult.SUCCESS -> break
			}
		}

		// TODO: different thread pool type?
		val threadPool = Executors.newFixedThreadPool(10)
		val completionService: CompletionService<DownloadTask> = ExecutorCompletionService(threadPool)
		tasks.forEach { t ->
			completionService.submit {
				t.download(opts.packFolder, clientHolder)
				t
			}
		}
		for (i in tasks.indices) {
			val task: DownloadTask = try {
				completionService.take().get()
			} catch (e: InterruptedException) {
				ui.showErrorAndExit("Interrupted when consuming download tasks", e)
			} catch (e: ExecutionException) {
				ui.showErrorAndExit("Failed to execute download task", e)
			}
			// Update manifest - If there were no errors cachedFile has already been modified in place (good old pass by reference)
			task.cachedFile?.let { file ->
				if (task.failed()) {
					val oldFile = file.revert
					if (oldFile != null) {
						manifest.cachedFiles.putIfAbsent(task.metadata.file.rebase(opts.packFolder), oldFile)
					} else { null }
				} else {
					manifest.cachedFiles.putIfAbsent(task.metadata.file.rebase(opts.packFolder), file)
				}
			}

			val exDetails = task.exceptionDetails
			val progress = if (exDetails != null) {
				"Failed to download ${exDetails.name}: ${exDetails.exception.message}"
			} else {
				when (task.completionStatus) {
					DownloadTask.CompletionStatus.INCOMPLETE -> "${task.name} pending (you should never see this...)"
					DownloadTask.CompletionStatus.DOWNLOADED -> "Downloaded ${task.name}"
					DownloadTask.CompletionStatus.ALREADY_EXISTS_CACHED -> "${task.name} already exists (cached)"
					DownloadTask.CompletionStatus.ALREADY_EXISTS_VALIDATED -> "${task.name} already exists (validated)"
					DownloadTask.CompletionStatus.SKIPPED_DISABLED -> "Skipped ${task.name} (disabled)"
					DownloadTask.CompletionStatus.SKIPPED_WRONG_SIDE -> "Skipped ${task.name} (wrong side)"
					DownloadTask.CompletionStatus.DELETED_DISABLED -> "Deleted ${task.name} (disabled)"
					DownloadTask.CompletionStatus.DELETED_WRONG_SIDE -> "Deleted ${task.name} (wrong side)"
				}
			}
			ui.submitProgress(InstallProgress(progress, i + 1, tasks.size))

			if (ui.cancelButtonPressed) { // Stop all tasks, don't launch the game (it's in an invalid state!)
				// TODO: close client holder in more places?
				clientHolder.close()
				threadPool.shutdown()
				cancelled = true
				return
			}
		}

		// Shut down the thread pool when the update is done
		threadPool.shutdown()

		val failedTasks2ElectricBoogaloo = tasks.asSequence().map(DownloadTask::exceptionDetails).filterNotNull().toList()
		if (failedTasks2ElectricBoogaloo.isNotEmpty()) {
			errorsOccurred = true
			when (ui.showExceptions(failedTasks2ElectricBoogaloo, tasks.size, false)) {
				ExceptionListResult.CONTINUE -> {}
				ExceptionListResult.CANCEL -> cancelled = true
				ExceptionListResult.IGNORE -> cancelledStartGame = true
			}
		}

		// Delete files that are no longer part of the pack (removed mods, configs, resource packs, ...).
		// This also catches files that the local manifest doesn't know about (for example if it was
		// deleted, or the files were installed by hand), which would otherwise never be removed.
		if (opts.prune && !cancelled && !cancelledStartGame && !errorsOccurred) {
			deleteOrphanedFiles(indexFile, manifest)
		}
	}

	/**
	 * Deletes files that were installed by a previous update, but which are no longer part of the pack.
	 *
	 * The manifest records where files were installed, so it is used to find removed files.
	 */
	private fun deleteFilesRemovedFromPack(indexFile: IndexFile, manifest: ManifestFile) {
		val it: MutableIterator<Map.Entry<PackwizFilePath, ManifestFile.File>> = manifest.cachedFiles.entries.iterator()
		while (it.hasNext()) {
			val (uri, file) = it.next()
			if (indexFile.files.any { it.file.rebase(opts.packFolder) == uri }) {
				// Still part of the pack, don't touch it
				continue
			}
			val location = file.cachedLocation
			if (location == null) {
				// Nothing was ever installed for this entry, so there is nothing to delete
				it.remove()
				continue
			}
			try {
				if (Files.deleteIfExists(location.nioPath)) {
					Log.info("Deleted ${location.filename} (removed from pack)")
				}
				it.remove()
			} catch (e: IOException) {
				// Keep the manifest entry, so that the deletion is retried on the next update
				// (the file might be locked by a running game or server)
				errorsOccurred = true
				Log.warn("Failed to delete ${location.filename} (removed from pack), will retry on the next update", e)
			}
		}
	}

	/**
	 * Deletes files that are no longer part of the pack (removed mods, configs, resource packs, ...),
	 * without relying on the manifest to find all of them.
	 *
	 * Files that were installed by a previous update are found using the manifest, but leftover files
	 * inside the folders that the pack installs files into are removed as well, so that files which the
	 * manifest doesn't know about (for example when it was deleted, or files that were added by hand)
	 * cannot make the pack folder drift out of sync with the pack.
	 */
	private fun deleteOrphanedFiles(indexFile: IndexFile, manifest: ManifestFile) {
		// Resolve the destination of every file in the pack. If any of them cannot be resolved (for
		// example if its metadata failed to download) we don't know which files are still needed, so
		// nothing is deleted at all.
		val dests = ArrayList<PackwizFilePath>(indexFile.files.size)
		for (indexEntry in indexFile.files) {
			val dest = try {
				indexEntry.destURI.rebase(opts.packFolder)
			} catch (e: Exception) {
				Log.warn("Could not determine the destination of ${indexEntry.file}, not removing any leftover files", e)
				return
			}
			dests.add(dest)
		}

		// With --prune-all (or --full-sync) every file type is removed, otherwise only mod JARs are,
		// so that files which the pack doesn't manage (for example configs generated by the game) are
		// left alone
		val pruneAll = opts.pruneAll || opts.fullSync
		val wanted = HashSet<Path>()
		val managed = LinkedHashSet<String>()
		val folders = LinkedHashSet<String>()
		for (dest in dests) {
			wanted.add(dest.nioPath.normalize())
			val folder = managedFolderOf(dest) ?: continue
			managed.add(folder)
			if (pruneAll || dest.filename.endsWith(".jar", ignoreCase = true)) {
				folders.add(folder)
			}
		}

		// Remember the folders that the pack installs files into, so that folders which the pack stops
		// managing later can be cleaned up by full sync
		manifest.syncedFolders.addAll(managed)

		if (opts.fullSync) {
			// Full sync also cleans up folders that the pack managed in the past, so that files left
			// behind by mods that were removed from the pack cannot stay in the pack folder forever
			folders.addAll(manifest.syncedFolders)
		}

		// The manifest itself must never be deleted, even when it is stored inside a managed folder
		val protectedFiles = HashSet<Path>()
		protectedFiles.add(opts.manifestFile.nioPath.normalize())

		var deleted = 0
		for (folder in outermostFirst(folders)) {
			if (isRuntimeFolder(folder)) {
				Log.warn("Not removing leftover files in $folder, as it holds data that the game or server generates")
				continue
			}
			val folderPath = opts.packFolder.resolve(folder).nioPath
			deleted += deleteUnmanagedFilesIn(folderPath, wanted, protectedFiles, pruneAll, opts.fullSync)
			if (opts.fullSync) {
				try {
					// Only deletes the folder when nothing is left in it
					Files.delete(folderPath)
				} catch (e: IOException) {
					// Still contains files (or is in use), leave it alone
				}
			}
		}
		if (deleted > 0) {
			Log.info("Deleted $deleted file(s) that are no longer part of the pack")
		}
	}

	/**
	 * Folders that are never swept for leftover files, as they hold data (worlds, logs, backups, ...)
	 * that the game or server generates while running, and which the pack does not manage.
	 */
	private fun isRuntimeFolder(folder: String): Boolean {
		val top = folder.substringBefore('/').lowercase()
		return top == "world" || top.startsWith("world_") || top in runtimeFolders
	}

	/**
	 * The folder (relative to the pack folder) that a file is installed into, for example "mods" for
	 * "mods/example.jar" or "config/example" for "config/example/settings.toml".
	 *
	 * Files that are installed directly into the pack folder are not managed, as the pack folder also
	 * contains the game or server itself.
	 */
	private fun managedFolderOf(dest: PackwizFilePath): String? {
		val folder = dest.parent
		if (folder == opts.packFolder) {
			return null
		}
		val parts = relativePathOf(folder)
		if (parts.isEmpty()) {
			return null
		}
		return parts.joinToString("/")
	}

	/**
	 * The path of [path] relative to the pack folder, as a list of path components.
	 */
	private fun relativePathOf(path: PackwizFilePath): List<String> {
		val parts = ArrayList<String>()
		var current = path
		while (current != opts.packFolder) {
			parts.add(current.filename)
			val up = current.parent
			if (up == current) {
				break
			}
			current = up
		}
		parts.reverse()
		return parts
	}

	/**
	 * Sorts folders so that a folder is always handled before the folders inside it, as sweeping the
	 * outer folder covers the inner ones as well.
	 */
	private fun outermostFirst(folders: Collection<String>): List<String> {
		val result = ArrayList<String>(folders.size)
		for (folder in folders.sorted()) {
			if (result.any { folder.startsWith("$it/") }) {
				continue
			}
			result.add(folder)
		}
		return result
	}

	/**
	 * Deletes files in [folder] that are not part of the pack, recursively.
	 */
	private fun deleteUnmanagedFilesIn(folder: Path, wanted: Set<Path>, protectedFiles: Set<Path>, pruneAll: Boolean, removeEmptyFolders: Boolean): Int {
		if (!Files.isDirectory(folder)) {
			return 0
		}
		var deleted = 0
		try {
			Files.newDirectoryStream(folder).use { entries ->
				for (entry in entries) {
					val name = entry.fileName.toString()
					// Hidden and disabled files belong to the user, not to the pack
					if (name.startsWith(".") || name.endsWith(".disabled")) {
						continue
					}
					if (Files.isDirectory(entry)) {
						deleted += deleteUnmanagedFilesIn(entry, wanted, protectedFiles, pruneAll, removeEmptyFolders)
						if (removeEmptyFolders) {
							try {
								// Only deletes the folder when nothing is left in it
								Files.delete(entry)
							} catch (e: IOException) {
								// Still contains files (or is in use), leave it alone
							}
						}
						continue
					}
					if (!Files.isRegularFile(entry)) {
						continue
					}
					val normalized = entry.normalize()
					if (normalized in wanted || normalized in protectedFiles) {
						continue
					}
					// Pack metadata files are kept, as the pack folder may be a packwiz pack itself
					if (name.endsWith(".pw.toml")) {
						continue
					}
					if (!pruneAll && !name.endsWith(".jar", ignoreCase = true)) {
						continue
					}
					try {
						if (Files.deleteIfExists(entry)) {
							Log.info("Deleted $name (no longer in the pack)")
							deleted++
						}
					} catch (e: IOException) {
						Log.warn("Failed to delete leftover file $name", e)
					}
				}
			}
		} catch (e: IOException) {
			Log.warn("Failed to check $folder for leftover files", e)
		}
		return deleted
	}

	enum class ResolveResult {
		RETRY,
		QUIT,
		SUCCESS;
	}

	private fun validateAndResolve(nonFailedFirstTasks: List<DownloadTask>, clientHolder: ClientHolder): ResolveResult {
		ui.submitProgress(InstallProgress("Validating existing files..."))

		// Validate existing files
		for (downloadTask in nonFailedFirstTasks.filter(DownloadTask::correctSide)) {
			downloadTask.validateExistingFile(opts.packFolder, clientHolder)
		}

		// Resolve CurseForge metadata
		val cfFiles = nonFailedFirstTasks.asSequence().filter { !it.alreadyUpToDate }
			.filter(DownloadTask::correctSide)
			.map { it.metadata }
			.filter { it.linkedFile != null }
			.filter { it.linkedFile!!.download.mode == DownloadMode.CURSEFORGE }.toList()
		if (cfFiles.isNotEmpty()) {
			ui.submitProgress(InstallProgress("Resolving CurseForge metadata..."))
			val resolveFailures = resolveCfMetadata(cfFiles, opts.packFolder, clientHolder)
			if (resolveFailures.isNotEmpty()) {
				errorsOccurred = true
				return when (ui.showExceptions(resolveFailures, cfFiles.size, true)) {
					ExceptionListResult.CONTINUE -> {
						ResolveResult.RETRY
					}
					ExceptionListResult.CANCEL -> {
						cancelled = true
						ResolveResult.QUIT
					}
					ExceptionListResult.IGNORE -> {
						cancelledStartGame = true
						ResolveResult.QUIT
					}
				}
			}
		}
		return ResolveResult.SUCCESS
	}

	private fun showCancellationDialog() {
		when (ui.showCancellationDialog()) {
			CancellationResult.QUIT -> cancelled = true
			CancellationResult.CONTINUE -> cancelledStartGame = true
		}
	}

	// TODO: move to UI?
	private fun handleCancellation() {
		if (cancelled) {
			println("Update cancelled by user!")
			exitProcess(1)
		} else if (cancelledStartGame) {
			println("Update cancelled by user! Continuing to start game...")
			exitProcess(0)
		}
	}

}

// Folders holding data generated by the game or server, which is never swept for leftover files
private val runtimeFolders = setOf(
	"backups", "cache", "crash-reports", "libraries", "local", "logs", "saves", "screenshots", "versions"
)