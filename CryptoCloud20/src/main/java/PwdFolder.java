import com.dropbox.core.DbxException;
import com.dropbox.core.v2.sharing.AccessLevel;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.javatuples.Pair;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

public class PwdFolder {
	private final String name;
	private final User owner;
	private final Vault vault;
	private final List<Pair<Group, AccessLevel>> groupsAccesses;

	private PwdFolder(PwdFolderBuilder builder) {
		this.name = builder.name;
		this.owner = builder.owner;
		this.vault = builder.vault;
		this.groupsAccesses = builder.groupsAccesses;
	}

	@Override
	public String toString() {
		return this.name;
	}

	@Override
	public boolean equals(Object other) {
		if (other == null) return false;
		if (other == this) return true;
		if (!(other instanceof PwdFolder)) return false;
		PwdFolder otherMyClass = (PwdFolder) other;
		return (this.name.equals(otherMyClass.getName()));

	}

	User getOwner() {
		if (owner == null) {
			throw new IllegalStateException("Owner not initialized.");
		}
		return owner;
	}

	String getName() {
		if (name == null) {
			throw new IllegalStateException("Name not initialized.");
		}
		return name;
	}

	Vault getVault() {
		if (vault == null) {
			throw new IllegalStateException("Vault not initialized.");
		}
		return vault;
	}

	List<Pair<Group, AccessLevel>> getGroupsAccesses() {
		if (groupsAccesses == null) {
			throw new IllegalStateException("GroupAccesses not initialized.");
		}
		return groupsAccesses;
	}


	private JsonObject toJson() {
		JsonObject jsonObject = new JsonObject();
		jsonObject.add(Notify.TypeMemberName.PWDFOLDER.toString(), new JsonParser().parse(this.name));
		jsonObject.add(Notify.TypeMemberName.PASSWORD.toString(), new JsonParser().parse(this.vault.getPassword()));
		jsonObject.add(Notify.TypeMemberName.OWNER.toString(), new JsonParser().parse(this.owner.getEmail()));
		jsonObject.add(Notify.TypeMemberName.GROUPSACCESSES.toString(), new JsonParser().parse(this.groupsAccesses.toString()));
		return jsonObject;
	}

	PwdFolder upload(Caller caller) {
		try {
			if (caller.equals(this.owner)) {
				byte[] infoByte = toJson().toString().getBytes();
				FileSystem fileSystem = Vault.getPersonalVault().open();
				if (fileSystem != null) {
					Files.write(fileSystem.getPath(Vault.MY_PWDFOLDER.resolve(this.name).toString()), infoByte);
					fileSystem.close();
				} else {
					throw new Main.ExecutionException("upload");
				}

			}
		} catch (IOException e) {
			throw new Main.ExecutionException("upload", e, this);
		}
		return this;
	}

	void shareUsers(Group group, AccessLevel accessLevel, List<User> users) {
		try {
			Dropbox.addUsersToFolder(accessLevel,
					Dropbox.BASE.resolve(this.name), users);
			users.forEach(user -> new Notify().setPwdFolderShared(this, group, accessLevel)
					.encrypt(user.getPublicKey())
					.upload(user.OWN_MESSAGE_PASSING));

		} catch (DbxException e) {
			throw new Main.ExecutionException("shareUsers", e, this);
		}
	}

	PwdFolder share(List<Pair<Group, AccessLevel>> groupsAccesses) {
		groupsAccesses.forEach(groupAccess -> {
			//share members
			shareUsers(groupAccess.getValue0(), groupAccess.getValue1(), groupAccess.getValue0().getMembers());
			//share owner
			shareUsers(groupAccess.getValue0(), groupAccess.getValue1(), Collections.singletonList(groupAccess.getValue0().getOwner()));
		});
		return this;
	}

	//TODO TEST
	private boolean checkIfRemove(Group group, User user) {
		List<Group> groups = new ArrayList<>();
		if (this.getOwner().equals(user)) {
			return false;
		}
		this.getGroupsAccesses().forEach(pair -> groups.add(pair.getValue0()));
		AtomicBoolean flag = new AtomicBoolean(true);
		groups.forEach(savedGroup -> {
			if (!group.equals(savedGroup)) {
				if (savedGroup.getMembers().contains(user)) {
					flag.set(false);
				} else if (savedGroup.getOwner().equals(user)) {
					flag.set(false);
				}
			}
		});
		return flag.get();
	}

	void unshareUsers(List<User> userList, Group group) {
		userList.forEach(user -> {

			if (checkIfRemove(group, user)) {
				try {
					//remove access members
					Dropbox.removeUsersFromFolder(Dropbox.BASE.resolve(name), Collections.singletonList(user));

				} catch (DbxException e) {
					throw new Main.ExecutionException("unshare", e, this);
				}
			}
			//send member sempre fatta
			new Notify().setPwdFolderRemoved(group, this)
					.encrypt(user.getPublicKey()).upload(user.OWN_MESSAGE_PASSING).localDelete();


		});
	}

	PwdFolder unshare(List<Group> groups) {
		groups.forEach(group -> {
			//TODO controllare se deve cambiare di permessi
			unshareUsers(group.getMembers(), group);
			unshareUsers(Collections.singletonList(group.getOwner()), group);

		});
		Main.success("unshare");

		return this;
	}

	void delete() {
		try {
			FileSystem fileSystem = Vault.getPersonalVault().open();
			String id = Dropbox.getSharedFolderId(Dropbox.BASE.resolve(name));

			Dropbox.getClient().sharing().unshareFolder(id);
			//for each group
			this.getGroupsAccesses().forEach(pair -> {
				//send members
				pair.getValue0().getMembers().forEach(user ->
						new Notify().setPwdFolderRemoved(pair.getValue0(), this)
								.encrypt(user.getPublicKey()).upload(user.OWN_MESSAGE_PASSING).localDelete());
				//send owner
				new Notify().setPwdFolderRemoved(pair.getValue0(), this)
						.encrypt(pair.getValue0().getOwner().getPublicKey())
						.upload(pair.getValue0().getOwner().OWN_MESSAGE_PASSING).localDelete();
				System.out.println(pair.getValue0().getOwner());

			});
			//TODO fix
			Thread.sleep(2000);
			Dropbox.getClient().files().deleteV2(Dropbox.BASE.resolve(name).toString());
			if (fileSystem != null) {
				Files.deleteIfExists(fileSystem.getPath(Vault.MY_PWDFOLDER.resolve(name).toString()));
				Main.success("deletePwdFolder");
				fileSystem.close();
			} else {
				throw new Main.ExecutionException("deletePwdFolder");
			}

		} catch (InterruptedException | IOException | DbxException e) {
			throw new Main.ExecutionException("deletePwdFolder", e, this);
		}

	}

	void localDelete() {
		if (groupsAccesses != null) {
			getGroupsAccesses().forEach(pair -> pair.getValue0().localDelete());
		}

	}

	public static class PwdFolderBuilder {
		private final String name;
		private User owner;
		private Vault vault;
		private List<Pair<Group, AccessLevel>> groupsAccesses;

		PwdFolderBuilder(String name) {
			this.name = name;
		}

		PwdFolderBuilder(String name, User owner, Vault vault, List<Pair<Group, AccessLevel>> groupsAccesses) {
			this.name = name;
			this.owner = owner;
			this.vault = vault;
			this.groupsAccesses = groupsAccesses;
		}

		PwdFolderBuilder setOwner(User owner) {
			this.owner = owner;
			return this;
		}

		PwdFolderBuilder setFromDropbox() {
			//1
			//download del file  da MyPwdFolder
			//set degli attributi
			FileSystem fileSystem = Vault.getPersonalVault().open();
			JsonParser parser = new JsonParser();
			try {
				if (fileSystem != null) {
					JsonObject jsonObject = parser.parse(new String(Files.readAllBytes(
							fileSystem.getPath(Vault.MY_PWDFOLDER.resolve(this.name).toString())))).getAsJsonObject();
					this.owner = new User.UserBuilder(jsonObject.get(Notify.TypeMemberName.OWNER.toString()).getAsString()).build();
					this.groupsAccesses = new ArrayList<>();
					JsonArray list = jsonObject.getAsJsonArray(Notify.TypeMemberName.GROUPSACCESSES.toString());
					list.forEach(pair -> {
						JsonArray jsonPair = pair.getAsJsonArray();
						groupsAccesses.add(new Pair<>(new Group.GroupBuilder(jsonPair.get(0).getAsString()).setFromDropbox().build(),
								AccessLevel.valueOf(jsonPair.get(1).getAsString())));

					});

					this.vault = new Vault(Vault.SLASH.resolve(this.name)).setPassword(jsonObject.get(Notify.TypeMemberName.PASSWORD.toString()).getAsString());
					fileSystem.close();
				} else {
					throw new Main.ExecutionException("setFromDropbox");
				}

			} catch (IOException e) {
				//2
				//ricerca del pwdfolder da tutti i gruppi dell'utente
				//download del file
				//set dei pochi attributi
				try {
					Optional<Path> foundDirectory = Files.list(fileSystem.getPath(Vault.SLASH.toString())).filter(folder -> {

						try {
							return Files.list(fileSystem.getPath(folder.toString())).anyMatch(file ->
									file.getFileName().toString().equals(this.name));
						} catch (IOException e1) {
							throw new Main.ExecutionException("setFromDropbox", e, this);
						}
					}).findFirst();
					if (foundDirectory.isPresent()) {
						try {
							JsonObject jsonObject = parser.parse(new String(Files.readAllBytes(
									fileSystem.getPath(foundDirectory.get().resolve(this.name).toString())))).getAsJsonObject();
							this.owner = new User.UserBuilder(jsonObject.get(Notify.TypeMemberName.OWNER.toString()).getAsString()).build();
							this.vault = new Vault(Vault.SLASH.resolve(this.name)).setPassword(jsonObject.get(Notify.TypeMemberName.PASSWORD.toString()).getAsString());
						} catch (IOException e1) {
							throw new Main.ExecutionException("setFromDropbox", e, this);
						}
					}
					fileSystem.close();
				} catch (IOException e1) {
					throw new Main.ExecutionException("setFromDropbox", e, this);
				}

			}
			return this;
		}

		PwdFolderBuilder setFrompath(Path path, FileSystem fileSystem) {
			try {
				JsonObject jsonObject = new JsonParser().parse(new String(Files.readAllBytes(
						fileSystem.getPath(path.toString())))).getAsJsonObject();
				this.owner = new User.UserBuilder(jsonObject.get(Notify.TypeMemberName.OWNER.toString()).getAsString()).setPublicKey().setVerified().build();
				this.vault = new Vault(Vault.SLASH.resolve(this.name)).setPassword(jsonObject.get(Notify.TypeMemberName.PASSWORD.toString()).getAsString());
			} catch (IOException e) {
				throw new Main.ExecutionException("setFromDropbox", e, this);
			}

			return this;
		}

		public PwdFolder build() {
			return new PwdFolder(this);
		}
	}
}
