import com.dropbox.core.DbxException;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

public class Group {
	private final String name;
	private final User owner;
	private final List<User> members;
	private final Boolean verified;

	private Group(GroupBuilder groupBuilder) {
		this.owner = groupBuilder.owner;
		this.members = groupBuilder.members;
		this.verified = groupBuilder.verified;
		this.name = groupBuilder.name;
	}

	@Override
	public String toString() {
		return this.getName();
	}

	@Override
	public boolean equals(Object other) {
		if (other == null) return false;
		if (other == this) return true;
		if (!(other instanceof Group)) return false;
		Group otherMyClass = (Group) other;
		return (this.name.equals(otherMyClass.getName()));

	}

	String getName() {
		if (name == null) {
			throw new IllegalStateException("Name not initialized.");
		}
		return name;
	}

	Boolean getVerified() {
		if (verified == null) {
			throw new IllegalStateException("Verified not initialized.");
		}
		return verified;
	}

	List<User> getMembers() {
		if (members == null) {
			throw new IllegalStateException("Members not initialized.");
		}
		return members;
	}

	User getOwner() {
		if (owner == null) {
			throw new IllegalStateException("Owner not initialized.");
		}
		return owner;
	}

	Group delete() {
		try {
			//remove file in group composition
			Dropbox.getClient().files().deleteV2(Dropbox.GROUPS_COMPOSITION.resolve(this.name).toString());
			//remove file in signed group
			Dropbox.getClient().files().deleteV2(Dropbox.SIGNED_GROUPS.resolve(this.name + Main.END_SIGNED).toString());
			//send notification admin
			new Notify().setGroupRemoved(this, this.getOwner(), null).encrypt(Caller.getAdminPublicKey()).upload(Caller.getAdminMP());
			//send notification ad ogni owner di owni pwdfolder
			pwdFoldersShared().forEach(pwdFolder ->
					new Notify().setGroupRemoved(this, this.getOwner(), pwdFolder)
							.encrypt(pwdFolder.getOwner().getPublicKey())
							.upload(pwdFolder.getOwner().OWN_MESSAGE_PASSING)
			);
			//remove folder nella vault
			FileSystem fileSystem = Vault.getPersonalVault().open();
			if (fileSystem != null) {
				Files.list(fileSystem.getPath(Vault.SLASH.resolve(this.name).toString())).forEach(path -> {
					try {
						Files.deleteIfExists(path);
					} catch (IOException e) {
						throw new Main.ExecutionException("delete");
					}
				});
				Files.deleteIfExists(fileSystem.getPath(Vault.SLASH.resolve(this.name).toString())); //todo check, forse vuole prima la rimozione interna
				fileSystem.close();
			} else {
				throw new Main.ExecutionException("delete");
			}
			return this;
		} catch (DbxException | IOException e) {
			throw new Main.ExecutionException("delete", e, this);
		}
	}

	private JsonObject toJSON() {
		JsonObject jsonObject = new JsonObject();
		jsonObject.add(Notify.TypeMemberName.GROUP.toString(), new JsonParser().parse(this.name));
		jsonObject.add(Notify.TypeMemberName.OWNER.toString(), new JsonParser().parse(this.owner.getEmail()));
		jsonObject.add(Notify.TypeMemberName.MEMBERS.toString(), new JsonParser().parse(this.members.toString()));
		return jsonObject;
	}

	Group upload(Caller caller) {
		try {
			if (caller.equals(this.owner)) {
				byte[] infoByte = toJSON().toString().getBytes();
				Path info = Files.write(Main.MY_TEMP_PATH.resolve(this.name), infoByte);
				Path sign = Files.write(Main.MY_TEMP_PATH.resolve(this.name + Main.END_SIGNED),
						caller.generatePkcs1Signature(owner.getPrivateKey(), infoByte));
				Dropbox.upload(info, Dropbox.GROUPS_COMPOSITION.resolve(info.getFileName()));
				Dropbox.upload(sign, Dropbox.SIGNED_GROUPS.resolve(sign.getFileName()));
				new Notify().setGroupCreated(caller, this)
						.encrypt(Caller.getAdminPublicKey()).upload(Caller.getAdminMP()).localDelete();
			}
		} catch (IOException | DbxException e) {
			throw new Main.ExecutionException("upload", e, this);
		}
		return this;
	}

	Group warnAdded(List<User> newUsers) {
		//TODO CHECK
		//invia una notifica ad ogni owner di ogni pwdFolder che è condiviso con il gruppo
		pwdFoldersShared().forEach(pwdFolder ->
				new Notify().setUsersAddedOrRemoved(this, pwdFolder, newUsers, Notify.TypeNotification.USERS_ADDED_TO_GROUP)
						.encrypt(pwdFolder.getOwner().getPublicKey())
						.upload(pwdFolder.getOwner().OWN_MESSAGE_PASSING));
		return this;
	}

	private List<PwdFolder> pwdFoldersShared() {
		try {
			List<PwdFolder> pwdFolders = new ArrayList<>();
			FileSystem fileSystem = Vault.getPersonalVault().open();
			if (fileSystem != null) {
				if (Files.exists(fileSystem.getPath(Vault.SLASH.resolve(this.name).toString()))) {
					Stream<Path> pathStream = Files.list(fileSystem.getPath(Vault.SLASH.resolve(this.name).toString()));
					//if(pathStream.findFirst().isPresent()){
					pathStream.forEach(path -> pwdFolders.add(new PwdFolder.PwdFolderBuilder(path.getFileName().toString()).setFrompath(path, fileSystem).build()));
					//}

				}
				fileSystem.close();
				return pwdFolders;
			} else {
				throw new Main.ExecutionException("pwdFoldersShared2");
			}
		} catch (IOException e) {
			throw new Main.ExecutionException("pwdFoldersShared1", e, this);
		}
	}

	Group warnRemoved(List<User> exUsers) {
		//TODO CHECK
		//invia una notifica ad ogni owner di ogni pwdFolder che è condiviso con il gruppo
		pwdFoldersShared().forEach(pwdFolder ->
				new Notify().setUsersAddedOrRemoved(this, pwdFolder, exUsers, Notify.TypeNotification.USERS_REMOVED_FROM_GROUP)
						.encrypt(pwdFolder.getOwner().getPublicKey())
						.upload(pwdFolder.getOwner().OWN_MESSAGE_PASSING));

		return this;
	}

	void localDelete() {
		Path info = Main.MY_TEMP_PATH.resolve(this.name);
		Path sign = Main.MY_TEMP_PATH.resolve(this.name + Main.END_SIGNED);
		Path signA = Main.MY_TEMP_PATH.resolve(this.name + Main.END_ADMIN);
		Main.deleteLocalFiles(info, sign, signA);
		if (members != null) {
			members.forEach(User::localDelete);
		}

	}

	public static class GroupBuilder {
		private final String name;
		private User owner;
		private List<User> members;
		private Boolean verified;

		GroupBuilder(String name) {
			this.name = name;
		}

		GroupBuilder(String name, User owner, List<User> members) {
			this.name = name;
			this.owner = owner;
			this.members = members;
			this.verified = false;
			//	this.sign=null;
		}

		private void setAttributes(Path path) {
			try {
				JsonParser parser = new JsonParser();
				JsonObject jsonObject = parser.parse(new String(Files.readAllBytes(path))).getAsJsonObject();
				this.owner = new User
						.UserBuilder(jsonObject.get(Notify.TypeMemberName.OWNER.toString()).getAsString())
						.setPublicKey().setVerified().build();
				this.members = new ArrayList<>();
				jsonObject.getAsJsonArray(Notify.TypeMemberName.MEMBERS.toString()).forEach(single ->
						this.members.add(new User.UserBuilder(single.getAsString()).build()));
			} catch (IOException e) {
				throw new Main.ExecutionException("setAttributes", e, this);
			}

		}

		GroupBuilder setFromDropbox() {
			try {
				//prima parte: firma dell'owner sul file info
				Path signatureFile = Dropbox.download(Dropbox.SIGNED_GROUPS, name, Main.END_SIGNED);
				byte[] signature = Files.readAllBytes(signatureFile);
				Path infoFile = Dropbox.download(Dropbox.GROUPS_COMPOSITION, name, "");
				byte[] info = Files.readAllBytes(infoFile);
				setAttributes(infoFile);
				Boolean verified = Main.verifyPkcs1Signature(owner.getPublicKey(), info, signature);
				if (verified) {
					try {
						//seconda parte: firma dell'admin sulla chiave pubblica dell'owner
						Path signatureFileA = Dropbox
								.download(Dropbox.SIGNED_GROUPS_OWNER, name, Main.END_ADMIN);
						byte[] signatureA = Files.readAllBytes(signatureFileA);
						this.verified = Main.verifyPkcs1Signature(Caller.getAdminPublicKey(),
								owner.getPublicKey().getEncoded(), signatureA);
					} catch (IOException | DbxException e) {
						this.verified = false;
					}

				}
			} catch (IOException | DbxException e) {
				throw new Main.ExecutionException("setFromDropbox", e, this);
			}
			return this;
		}

		public Group build() {
			return new Group(this);
		}
	}
}
