package Execution;

import Management.Admin;
import Management.Caller;
import Management.Cloud.Dropbox;
import Management.User;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.PublicKey;
import java.security.Signature;
public class Main {
	//TODO se l'utente chiede conferma all'admin per la creaione del pwdfolder -> evito duplicati
	//todo problema: l'admin deve avere accesso a tutti i pwdfolder -> account critico
	public final static Path MY_TEMP_PATH = Paths.get(System.getProperty("java.io.tmpdir"));
	public final static Path BASE_PATH = Paths.get(System.getProperty("user.home"));
	public final static Path MY_PERSONAL_PATH = BASE_PATH.resolve("CryptoCloud");
	public final static String END_PUBLIC = ".public";
	public final static String END_PRIVATE = ".private";
	public final static String END_SIGNED = ".sign";
	public final static String END_ADMIN = ".admin";

	private static SecretKey secretkey;

	public static SecretKey getSecretkey() {
		if (secretkey == null) {
			throw new IllegalStateException("SecretKey not initialized.");
		}
		return secretkey;
	}

	//this is not used for security but to avoid users to mess with files in the dropbox folders
	private static void initSecretKey() {

		byte[] key = "OssigenoCryptoCloudSimon".getBytes();
		secretkey = new SecretKeySpec(key, "AES");
		/*
		try {
		//	secretkey = SecretKeyFactory.getInstance("AES", "BC").generateSecret(keySpec);
			secretkey = SecretKeyFactory.getInstance("AES").generateSecret(keySpec);
		} catch (InvalidKeySpecException  |NoSuchProviderException | NoSuchAlgorithmException e) {
			throw new ExecutionException("initSecretKey", e);
		}
			*/
	}

	public static void success(String nameFunction) {
		System.out.println("Function " + nameFunction + " completed with success.");
	}

	public static boolean verifyPkcs1Signature(PublicKey rsaPublic, byte[] input,
	                                           byte[] encSignature) {
		try {
			//Signature signature = Signature.getInstance("SHA384withRSA",
			//		"BC");
			Signature signature = Signature.getInstance("SHA384withRSA");

			signature.initVerify(rsaPublic);
			signature.update(input);
			return signature.verify(encSignature);
		} catch (Exception e) {
			throw new Main.ExecutionException("verifySignature", e);

		}
	}

	public static void deleteLocalFiles(Path... paths) {
		for (Path path : paths) {
			try {
				Files.deleteIfExists(path);
			} catch (IOException e) {
				throw new ExecutionException("deleteLocalFiles", e);
			}

		}

	}

	public static String inputUser() {
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

	public static void deleteDirectory(Path path) {
		try {
			Files.list(path).forEach(pathInternal -> {
				try {
					Files.deleteIfExists(pathInternal);
				} catch (IOException e) {
					throw new Main.ExecutionException("delete");
				}
			});
			Files.deleteIfExists(path);
		} catch (IOException e) {
			throw new Main.ExecutionException("deleteDirectory");
		}

	}

	public static void main(String args[]) {
//		Security.addProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider());

		Dropbox.initDropboxClient();
		initSecretKey();
		Caller caller = new Caller(new User.UserBuilder(Dropbox.getCallerEmail()).setCaller());
		System.out.println("press 0 for admin, 1 for user");
		int value = Integer.valueOf(Main.inputUser());
		if (Dropbox.checkIfAdmin() != value) { //settare 0
			Admin admin = new Admin(caller);
			admin.setup();
			manageInput(admin);
		} else {
			caller.setup();
			manageInput(caller);
		}
		System.exit(1);

	}

	private static void manageInput(Caller caller) {
		String input = inputUser();
		while (!input.equals("exit")) {
			if (caller instanceof Admin) {
				switch (input) {
					case "help":
						help(caller);
						break;
					case "signUser":
						((Admin) caller).signUser();
						break;
					case "removeSignUser":
						((Admin) caller).designUser();
						break;
					case "signGroup":
						((Admin) caller).signGroup();
						break;
					case "removeSignGroup":
						((Admin) caller).designGroup();
						break;
					case "addUsersToFileSystem":
						((Admin) caller).addUsersToFileSystem();
						break;
					case "removeUsersFromFileSystem":
						((Admin) caller).removeUsersFromFileSystem();
						break;
					default:
						System.err.println("Command not recognized");

				}

			} else {
				if (caller.getVerified()) {
					switch (input) {
						case "help":
							help(caller);
							break;
						case "recreateKeys":
							caller.reCreateKeys();
							break;
						case "mountSystem":
							caller.createFileSystem();
							break;
						case "listUsers":
							caller.listUsers().forEach(System.out::println);
							break;
						case "listGroups":
							caller.listGroups().forEach(System.out::println);
							break;
						case "listOwningPwdFolders":
							caller.listPwdFolders().forEach(System.out::println);
							break;
						case "listPwdEntries":
							caller.listPwdEntries().forEach(System.out::println);
							break;
						case "createGroup":
							caller.createGroup();
							break;
						case "addMembersToGroup":
							caller.addMembersToGroup();
							break;
						case "removeMembersFromGroup":
							caller.removeMembersFromGroup();
							break;
						case "deleteGroup":
							caller.deleteGroup();
							break;
						case "createPwdFolder":
							caller.createPwdFolder();
							break;
						case "addGroupsToPwdFolder":
							caller.addGroupsToPwdFolder();
							break;
						case "removeGroupsFromPwdFolder":
							caller.removeGroupsFromPwdFolder();
							break;
						case "deletePwdFolder":
							caller.deletePwdFolder();
							break;
						case "createPwdEntry":
							caller.createPwdEntry();
							break;
						case "showPwdEntry":
							caller.showPwdEntry();
							break;
						case "modifyPwdEntry":
							caller.modifyPwdEntry();
							break;
						case "deletePwdEntry":
							caller.deletePwdEntry();
							break;

						default:
							System.err.println("Command not recognized");

					}
				} else {
					System.err.println("Please wait for the Admin signature");
					break;
				}

			}
			input = inputUser();
		}
	}

	private static void help(Caller caller) {
		System.out.println("There are the possible operations:");
		if (caller instanceof Admin) {
			System.out.println("signUser");
			System.out.println("removeSignUser");
			System.out.println("signGroup");
			System.out.println("removeSignGroup");
			System.out.println("addUsersToFileSystem");
			System.out.println("removeUsersFromFileSystem");
		} else {
			System.out.println("recreateKeys");
			System.out.println("mountSystem");
			System.out.println("listUsers");
			System.out.println("listGroups");
			System.out.println("listOwningPwdFolders");
			System.out.println("listPwdEntries");
			System.out.println("createGroup");
			System.out.println("deleteGroup");
			System.out.println("addMemberToGroup");
			System.out.println("removeMemberFromGroup");
			System.out.println("createPwdFolder");
			System.out.println("deletePwdFolder");
			System.out.println("addGroupToPwdFolder");
			System.out.println("removeGroupFromPwdFolder");
			System.out.println("openPwdFolder");
			System.out.println("createPwdEntry");
		}
		System.out.println("exit");

	}

	public static class ExecutionException extends RuntimeException {
		public ExecutionException(String functionName) {
			super("Unable to execute function " + functionName);
		}

		public ExecutionException(String functionName, Throwable cause) {
			super("Unable to execute function " + functionName, cause);
		}

		public ExecutionException(String functionName, Throwable cause, User caller) {
			super("The user " + caller + " was unable to execute function" + functionName, cause);
		}

		public ExecutionException(String functionName, Throwable cause, Object caller) {
			super("The object " + caller.toString() + " was unable to execute function" + functionName, cause);
		}

	}

}
