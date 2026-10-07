package link.infra.packwiz.installer.target

import link.infra.packwiz.installer.util.Log
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okio.FileSystem
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

class ClientHolder {
	// Tries 10s timeouts (default), then 15s timeouts, then 60s timeouts
	private val retryTimes = arrayOf(15, 60)

	// TODO: a button to increase timeouts temporarily when retrying? manual retry button?
	val okHttpClient by lazy { OkHttpClient.Builder()
		// Force HTTP/1.1: HTTP/2 multiplexes all same-host requests onto ONE TCP connection, so
		// parallel file downloads were all sharing a single connection's bandwidth (measured
		// ~18 KB/s per connection, ~57 KB/s with 4 separate connections on this link).
		// HTTP/1.1 gives each concurrent request its own connection.
		.protocols(listOf(Protocol.HTTP_1_1))
		// Retry requests according to retryTimes list
		.addInterceptor {
			val req = it.request()

			var lastException: SocketTimeoutException? = null
			var res: Response? = null

			try {
				res = it.proceed(req)
			} catch (e: SocketTimeoutException) {
				lastException = e
			}

			var tryCount = 0
			while (res == null && tryCount < retryTimes.size) {
				Log.info("OkHttp connection to ${req.url} timed out; retrying... (${tryCount + 1}/${retryTimes.size})")

				val longerTimeoutChain = it
					.withConnectTimeout(retryTimes[tryCount], TimeUnit.SECONDS)
					.withReadTimeout(retryTimes[tryCount], TimeUnit.SECONDS)
					.withWriteTimeout(retryTimes[tryCount], TimeUnit.SECONDS)
				try {
					res = longerTimeoutChain.proceed(req)
				} catch (e: SocketTimeoutException) {
					lastException = e
				}

				tryCount++
			}

			res ?: throw lastException!!
		}
		.build() }

	val fileSystem = FileSystem.SYSTEM

	fun close() {
		okHttpClient.dispatcher.cancelAll()
		okHttpClient.dispatcher.executorService.shutdown()
		okHttpClient.connectionPool.evictAll()
	}
}