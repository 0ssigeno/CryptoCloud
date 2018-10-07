package Management;

import Execution.Main;
import Management.Cloud.Dropbox;
import com.dropbox.core.DbxException;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.IvParameterSpec;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
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
				Main.deleteDirectory(Vault.SLASH.resolve(this.name));
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

	private byte[] encrypt(byte[] bytes) {
		try {
			byte[] iv = {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
			IvParameterSpec ivspec = new IvParameterSpec(iv);
			Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
			cipher.init(Cipher.ENCRYPT_MODE, Main.secretkey, ivspec);
			return cipher.doFinal(bytes);

		} catch (InvalidAlgorithmParameterException | NoSuchAlgorithmException | NoSuchPaddingException | IllegalBlockSizeException | InvalidKeyException | BadPaddingException e) {
			throw new Main.ExecutionException("encrypt", e);
		}

	}

	Group upload(Caller caller) {
		try {
			if (caller.equals(this.owner)) {
				byte[] infoByte = toJSON().toString().getBytes();
				byte[] encrypted = encrypt(infoByte);
				Path info = Files.write(Main.MY_TEMP_PATH.resolve(this.name), encrypted);


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

	//TODO TEST
	Group warnAdded(List<User> newUsers) {
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
					pathStream.forEach(path -> pwdFolders.add(new PwdFolder.PwdFolderBuilder(path.getFileName().toString()).setFromPath(path, fileSystem).build()));
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

	//TODO TEST
	Group warnRemoved(List<User> exUsers) {

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

	static class GroupBuilder {
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

		private void setAttributes(byte[] content) {
			JsonParser parser = new JsonParser();
			JsonObject jsonObject = parser.parse(new String(content)).getAsJsonObject();
			this.owner = new User
					.UserBuilder(jsonObject.get(Notify.TypeMemberName.OWNER.toString()).getAsString())
					.setPublicKey().setVerified().build();
			this.members = new ArrayList<>();
			jsonObject.getAsJsonArray(Notify.TypeMemberName.MEMBERS.toString()).forEach(single ->
					this.members.add(new User.UserBuilder(single.getAsString()).build()));
		}

		GroupBuilder setFromDropbox() {
			try {
				//prima parte: firma dell'owner sul file info
				Path infoFile = Dropbox.download(Dropbox.GROUPS_COMPOSITION, name, "");
				byte[] info = decrypt(Files.readAllBytes(infoFile));

				setAttributes(info);
				Path signatureFile = Dropbox.download(Dropbox.SIGNED_GROUPS, name, Main.END_SIGNED);
				byte[] signature = Files.readAllBytes(signatureFile);
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
				verified = false;
			}
			return this;
		}

		private byte[] decrypt(byte[] bytes) {
			try {
				byte[] iv = {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
				IvParameterSpec ivspec = new IvParameterSpec(iv);

				Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
				cipher.init(Cipher.DECRYPT_MODE, Main.secretkey, ivspec);
				return cipher.doFinal(bytes);

			} catch (InvalidAlgorithmParameterException | NoSuchAlgorithmException | NoSuchPaddingException | IllegalBlockSizeException | InvalidKeyException | BadPaddingException e) {
				throw new Main.ExecutionException("decrypt", e);
			}

		}

		Group build() {
			return new Group(this);
		}
	}
}
