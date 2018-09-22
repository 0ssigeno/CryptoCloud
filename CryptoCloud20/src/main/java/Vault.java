
import org.cryptomator.cryptofs.CryptoFileSystemProperties;
import org.cryptomator.cryptofs.CryptoFileSystemProvider;
import org.cryptomator.cryptofs.CryptoFileSystemUri;

import javax.annotation.Nullable;
import java.io.IOException;
import java.net.URI;
import java.nio.file.*;

public class PersonalStorage {
	private static final Path PERSONAL_FOLDER= Main.BASE_PATH.resolve("Dropbox").resolve("PersonalFolder");
	private Path pathStorage;
	private Path pathPassword;
	private String password;
	static PersonalStorage personalStorage=null;

	public static PersonalStorage getPersonalStorage() {
		if (personalStorage == null) {
			throw new IllegalStateException("PersonalStorage not initialized.");
		}
		return personalStorage;
	}

	public Path getPathPassword() {
		if (pathPassword == null) {
			throw new IllegalStateException("PathPassword not initialized.");
		}
		return pathPassword;
	}
	public Path getPathStorage() {
		if (pathStorage == null) {
			throw new IllegalStateException("PathStorage not initialized.");
		}
		return pathStorage;
	}

	public String getPassword() {
		if (password == null) {
			throw new IllegalStateException("Password not initialized.");
		}
		return password;
	}

	PersonalStorage(Path pathStorage, String password){
		this.password=password;
		this.pathStorage=pathStorage;
	}
	PersonalStorage(Path pathStorage){
		this.pathStorage=pathStorage;
	}

	static void initPersonalStorage(){
		if(personalStorage==null){
			try{
				personalStorage=new PersonalStorage(PERSONAL_FOLDER);
				personalStorage.pathPassword=Main.MY_PERSONAL_PATH.resolve("PersonalPassword");
				if(Files.exists(personalStorage.pathStorage) && Files.exists(personalStorage.pathPassword)){
					personalStorage.password=new String(Files.readAllBytes(personalStorage.pathPassword));
				}
				else if(Files.exists(personalStorage.pathStorage) && !Files.exists(personalStorage.pathPassword)){
					boolean flag=true;
					System.out.println("Please enter the password you chose");
					while (flag){
						personalStorage.password=Main.inputUser();
						FileSystem fileSystem=personalStorage.open();
						if(fileSystem!=null){
							Files.write(personalStorage.pathPassword, personalStorage.password.getBytes());
							personalStorage.close(fileSystem);
							flag=false;
						}else{
							System.err.println("Wrong password");

						}
					}



				}
				else{
					System.out.println("Please enter a password: DO NOT FORGET IT");
					personalStorage.password=Main.inputUser();
					personalStorage.create();
					Files.write(personalStorage.pathPassword, personalStorage.password.getBytes());

				}
			}catch (IOException e){
				throw new Main.ExecutionException("initPersonalStorage",e);

			}

			Main.success("initPersonalStorage");
		}

	}
	void create(){
		try {
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
	public void close(FileSystem fileSystem) {
		try {
			fileSystem.close();
		} catch (Exception e) {
			throw new Main.ExecutionException("close",e,this);
		}

	}
}
