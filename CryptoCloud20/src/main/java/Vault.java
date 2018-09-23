import org.cryptomator.cryptofs.CryptoFileSystemProperties;
import org.cryptomator.cryptofs.CryptoFileSystemProvider;
import org.cryptomator.cryptofs.CryptoFileSystemUri;

import javax.annotation.Nullable;
import javax.crypto.Cipher;
import java.io.IOException;
import java.net.URI;
import java.nio.file.*;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.util.Random;

class Vault {
	static final Path BASE_PATH = Main.BASE_PATH.resolve("Dropbox");
	static final Path MY_PWDFOLDER = Paths.get("/MyPwdFolder");
	static final Path SLASH = Paths.get("/");
	private Path pathStorage;
	private Path pathPassword;
	private String password;
	private static Vault personalVault;

	Vault(Path pathStorage) {
		this.pathStorage = pathStorage;
	}

	static Vault getPersonalVault() {
		if (personalVault == null) {
			throw new IllegalStateException("PersonalStorage not initialized.");
		}
		return personalVault;
	}

	private static String decrypt(PrivateKey privateKey, byte[] content) {
		try {
			Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
			cipher.init(Cipher.DECRYPT_MODE, privateKey);
			return new String(cipher.doFinal(content));
		} catch (Exception e) {
			throw new Main.ExecutionException("decrypt", e);
		}

	}

	private static byte[] encrypt(PublicKey publicKey, String password) {
		try {
			Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
			cipher.init(Cipher.ENCRYPT_MODE, publicKey);
			return cipher.doFinal(password.getBytes());

		} catch (Exception e) {
			throw new Main.ExecutionException("encrypt", e);
		}

	}

	static void initPersonalStorage(Caller caller) {
		//TODO test enc-dec
		if (personalVault == null) {
			try{
				final Path PERSONAL_FOLDER = BASE_PATH.resolve("PersonalFolder");
				personalVault = new Vault(PERSONAL_FOLDER);
				personalVault.pathPassword = Main.MY_PERSONAL_PATH.resolve("PersonalPassword");
				if (Files.exists(personalVault.pathStorage) && Files.exists(personalVault.pathPassword)) {
					personalVault.password = decrypt(caller.getPrivateKey(), Files.readAllBytes(personalVault.pathPassword));
				} else if (Files.exists(personalVault.pathStorage) && !Files.exists(personalVault.pathPassword)) {
					boolean flag=true;
					System.out.println("Please enter the password you chose");
					while (flag){
						personalVault.password = Main.inputUser();
						FileSystem fileSystem = personalVault.open();
						if(fileSystem!=null){
							Files.write(personalVault.pathPassword, encrypt(caller.getPublicKey(), personalVault.getPassword()));
							fileSystem.close();
							flag=false;
						}else{
							System.err.println("Wrong password");

						}
					}

				}
				else{
					System.out.println("Please enter a password: DO NOT FORGET IT");
					personalVault.password = Main.inputUser();
					personalVault.create();
					FileSystem fileSystem = personalVault.open();
					if (fileSystem != null) {
						Files.createDirectories(fileSystem.getPath(Vault.MY_PWDFOLDER.toString()));
						fileSystem.close();
					}
					Files.write(personalVault.pathPassword, encrypt(caller.getPublicKey(), personalVault.getPassword()));

				}
			}catch (IOException e){
				throw new Main.ExecutionException("initPersonalStorage",e);

			}

			Main.success("initPersonalStorage");
		}

	}

	Path getPathPassword() {
		if (pathPassword == null) {
			throw new IllegalStateException("PathPassword not initialized.");
		}
		return pathPassword;
	}

	Path getPathStorage() {
		if (pathStorage == null) {
			throw new IllegalStateException("PathStorage not initialized.");
		}
		return pathStorage;
	}

	String getPassword() {
		if (password == null) {
			throw new IllegalStateException("Password not initialized.");
		}
		return password;
	}

	Vault setPassword(String password) {
		this.password = password;
		return this;
	}

	Vault createPassword() {
		int length = 30;
		this.password = new RandomString(length).nextString();
		return this;
	}




	void create(){
		try {
			Dropbox.createFolder(Dropbox.BASE.resolve(pathStorage.getFileName()), true);
			if(!Files.exists(pathStorage)){
				Files.createDirectories(pathStorage);
			}
			CryptoFileSystemProvider.initialize(pathStorage, "masterkey.cryptomator", password);
		} catch (IOException e) {
			throw new Main.ExecutionException("create",e,this);
		}

	}

	@Nullable
	FileSystem open()  {
		URI uri = CryptoFileSystemUri.create(pathStorage);
		try {
			return FileSystems.newFileSystem(
					uri,
					CryptoFileSystemProperties.cryptoFileSystemProperties()
							.withPassphrase(password)
							.build());
		} catch (Exception e) {
			return null;
		}

	}


	public static class RandomString {

		/* Assign a string that contains the set of characters you allow. */
		private static final String symbols = "ABCDEFGJKLMNPRSTUVWXYZ0123456789";

		private final Random random = new SecureRandom();

		private final char[] buf;

		RandomString(int length) {
			if (length < 1)
				throw new IllegalArgumentException("length < 1: " + length);
			buf = new char[length];
		}

		String nextString() {
			for (int idx = 0; idx < buf.length; ++idx)
				buf[idx] = symbols.charAt(random.nextInt(symbols.length()));
			return new String(buf);
		}

	}

}
