import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

class PwdEntry {
	private final DateTimeFormatter format = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss");
	private String name;
	private String username;
	private String password;
	private String system;
	private LocalDateTime date;
	private User lastModifier;
	private PwdFolder pwdFolder;


	PwdEntry(String name) {
		this.name = name;
	}

	PwdEntry(Path path, PwdFolder.PwdFolderBuilder pwdFolderBuilder) {
		this.name = path.getFileName().toString();
		String[] parts = this.name.split("@");
		this.username = parts[0];
		this.system = parts[1];
		this.pwdFolder = pwdFolderBuilder.build();
		setFromPath(path);

	}

	PwdEntry(String username, String password, String system, User user, PwdFolder pwdFolder) {
		this.username = username;
		this.password = password;
		this.system = system;
		this.lastModifier = user;
		this.name = username + "@" + system;
		this.date = LocalDateTime.now();
		this.pwdFolder = pwdFolder;
	}

	@Override
	public boolean equals(Object obj) {
		if (obj == null) return false;
		if (obj == this) return true;
		if (!(obj instanceof PwdEntry)) return false;
		PwdEntry otherMyClass = (PwdEntry) obj;
		return (this.name.equals(otherMyClass.getName()));

	}

	@Override
	public String toString() {
		return name;
	}

	void setPassword(String password) {
		this.password = password;
	}

	void setDate(LocalDateTime date) {
		this.date = date;
	}

	void setLastModifier(User lastModifier) {
		this.lastModifier = lastModifier;
	}

	String getUsername() {
		if (username == null) {
			throw new IllegalStateException("Username not initialized.");

		}
		return username;

	}

	void setUsername(String username) {
		this.username = username;
	}

	String getName() {
		if (name == null) {
			throw new IllegalStateException("Name not initialized.");

		}
		return name;
	}

	void setName(String name) {
		this.name = name;
	}

	String getSystem() {
		if (system == null) {
			throw new IllegalStateException("System not initialized.");

		}
		return system;
	}

	void upload(String exName) {
		try {
			JsonObject jsonObject = this.toJson();
			FileSystem fileSystem = pwdFolder.getVault().open();
			if (fileSystem != null) {
				if (exName != null) {
					Files.deleteIfExists(fileSystem.getPath(Vault.SLASH + exName));
				}
				Files.write(fileSystem.getPath(Vault.SLASH + this.name), jsonObject.toString().getBytes());
				fileSystem.close();
			} else {
				throw new Main.ExecutionException("upload");
			}
		} catch (IOException e) {
			throw new Main.ExecutionException("upload", e, this);
		}


	}

	void read() {
		System.out.println("Username: " + username);
		System.out.println("System: " + system);
		System.out.println("Password: " + password);
		System.out.println("Last Modifier: " + lastModifier);
		System.out.println("Last Date Modified: " + date);
	}

	void delete() {
		FileSystem fileSystem = pwdFolder.getVault().open();
		if (fileSystem != null) {
			try {
				Files.deleteIfExists(fileSystem.getPath(Vault.SLASH + this.name));
				fileSystem.close();
			} catch (IOException e) {
				throw new Main.ExecutionException("delete", e, this);
			}
		} else {
			throw new Main.ExecutionException("delete");
		}
	}

	private JsonObject toJson() {
		JsonObject jsonObject = new JsonObject();
		String date = this.date.format(this.format);
		jsonObject.add(jsonValues.DATE.toString(), new JsonParser().parse(date));
		jsonObject.add(jsonValues.USERNAME.toString(), new JsonParser().parse(username));
		jsonObject.add(jsonValues.SYSTEM.toString(), new JsonParser().parse(system));
		jsonObject.add(jsonValues.PASSWORD.toString(), new JsonParser().parse(password));
		jsonObject.add(jsonValues.LAST_MODIFIER.toString(), new JsonParser().parse(lastModifier.getEmail()));


		return jsonObject;
	}

	private void setFromPath(Path path) {
		try {
			JsonObject jsonObject = new JsonParser().parse(new String(Files.readAllBytes(path))).getAsJsonObject();
			this.password = jsonObject.get(jsonValues.PASSWORD.toString()).getAsString();
			this.date = LocalDateTime.parse(jsonObject.get(jsonValues.DATE.toString()).getAsString(), this.format);
			this.lastModifier = new User.UserBuilder(jsonObject.get(jsonValues.LAST_MODIFIER.toString()).getAsString()).build();

		} catch (IOException e) {
			throw new Main.ExecutionException("setFromPath", e, this);
		}
	}

	private enum jsonValues {
		USERNAME,
		PASSWORD,
		SYSTEM,
		DATE,
		LAST_MODIFIER
	}

}
