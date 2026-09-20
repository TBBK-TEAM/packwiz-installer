package link.infra.packwiz.installer;

/**
 * Legacy entry point, kept so that anything which still launches the installer through this class
 * keeps working.
 *
 * packwiz-installer no longer requires packwiz-installer-bootstrap: running
 * {@code java -jar packwiz-installer.jar ...} starts the installer directly. (The bootstrapper
 * loads {@link Main} by name, it does not use the JAR's Main-Class attribute.)
 */
public class RequiresBootstrap {

	public static void main(String[] args) {
		Main.main(args);
	}

}
