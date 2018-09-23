import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.PublicKey;
import java.security.Signature;

public class Main {
	final static Path MY_TEMP_PATH = Paths.get(System.getProperty("java.io.tmpdir"));
	final static Path BASE_PATH = Paths.get(System.getProperty("user.home"));
	final static Path MY_PERSONAL_PATH = BASE_PATH.resolve("CryptoCloud");
	final static String END_PUBLIC = ".public";
	final static String END_PRIVATE = ".private";
	final static String END_SIGNED = ".sign";
	final static String END_ADMIN = ".admin";


	static void success(String nameFunction) {
		System.out.println("Function " + nameFunction + " completed with success.");
	}

	static boolean verifyPkcs1Signature(PublicKey rsaPublic, byte[] input,
	                                    byte[] encSignature) {
		try {
			Signature signature = Signature.getInstance("SHA384withRSA",
					"BC");
			signature.initVerify(rsaPublic);
			signature.update(input);
			return signature.verify(encSignature);
		} catch (Exception e) {
			throw new Main.ExecutionException("verifySignature", e);

		}
	}

	static void deleteLocalFiles(Path... paths) {
		for (Path path : paths) {
			try {
				Files.deleteIfExists(path);
			} catch (IOException e) {
				throw new ExecutionException("deleteLocalFiles", e);
			}

		}

	}

	static String inputUser() {
		try {
			String code = new BufferedReader(new InputStreamReader(System.in)).readLine();
			while (code == null || code.equals("\n") || code.equals("\t")
					|| code.equals("")) {
				System.out.println("Please write something");
				code = inputUser();
			}
			code = code.trim();
			return code;
		} catch (IOException e) {
			throw new ExecutionException("inputUser", e);
		}

	}

	public static void main(String args[]) {
		Dropbox.initDropboxClient();
		Caller caller = new Caller(new User.UserBuilder(Dropbox.getCallerEmail()).setCaller());
		Vault.initPersonalStorage(caller);
		if (Dropbox.checkIfAdmin() != 0) {
			Admin admin = new Admin(caller);
			admin.setup();

			admin.createGroup();
			admin.createPwdFolder();

			System.exit(0);

		} else {
			caller.setup();
			caller.deleteGroup();
			System.exit(0);

		}


	}

	static class ExecutionException extends RuntimeException {
		ExecutionException(String functionName) {
			super("Unable to execute function " + functionName);
		}

		ExecutionException(String functionName, Throwable cause) {
			super("Unable to execute function " + functionName, cause);
		}

		ExecutionException(String functionName, Throwable cause, User caller) {
			super("The user " + caller + " was unable to execute function" + functionName, cause);
		}

		ExecutionException(String functionName, Throwable cause, Object caller) {
			super("The object " + caller.toString() + " was unable to execute function" + functionName, cause);
		}

	}

}
