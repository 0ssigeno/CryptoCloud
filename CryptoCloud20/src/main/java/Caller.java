import com.dropbox.core.DbxException;
import com.dropbox.core.v2.files.ListFolderResult;
import com.dropbox.core.v2.files.Metadata;
import com.dropbox.core.v2.sharing.AccessLevel;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.javatuples.Pair;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

class Caller extends User {
	private static PublicKey adminPublicKey;
	private static Path adminMP;

	Caller(UserBuilder userBuilder) {
		super(userBuilder);
		try {
			Path path = Dropbox.download(Dropbox.SIGNED_PUBLIC_KEYS, "admin", Main.END_ADMIN);
			adminPublicKey = importPublic(path);
			Main.deleteLocalFiles(path);
		} catch (IOException | DbxException e) {
			adminPublicKey = null;
		}
		adminMP = Dropbox.MESSAGE_PASSING.resolve("admin");
	}


	static PublicKey getAdminPublicKey() {
		if (adminPublicKey == null) {
			throw new IllegalStateException("AdminPublicKey not initialized.");
		}
		return adminPublicKey;
	}

	static Path getAdminMP() {
		if (adminMP == null) {
			throw new IllegalStateException("AdminMP not initialized.");
		}
		return adminMP;
	}


	byte[] generatePkcs1Signature(PrivateKey rsaPrivate, byte[] input) {

		try {
			Signature signature = Signature.getInstance("SHA384withRSA", "BC");
			signature.initSign(rsaPrivate);
			signature.update(input);
			return signature.sign();
		} catch (Exception e) {
			throw new Main.ExecutionException("generateSignature", e, this);

		}
	}

	private void uploadPublicKey(Path publicKeyLocalPath) {
		Path path = Dropbox.PUBLIC_KEYS.resolve(getEmail() + Main.END_PUBLIC);
		if (!Dropbox.existFile(path)) {
			try {
				Dropbox.upload(publicKeyLocalPath, path);
				new Notify().setUserCreated(this).encrypt(adminPublicKey)
						.upload(adminMP).localDelete();
			} catch (IOException | DbxException e) {
				throw new Main.ExecutionException("uploadPublicKey", e, this);

			}
		}
	}

	void reCreateKeys() {
		//TODO TEST
		Path publicKeyPath = Main.MY_PERSONAL_PATH.resolve(getEmail() + Main.END_PUBLIC);
		Path privateKeyPath = Main.MY_PERSONAL_PATH.resolve(getEmail() + Main.END_PRIVATE);
		KeyPair keypair = generateKeyPair();
		setPublicKey(keypair.getPublic());
		setPrivateKey(keypair.getPrivate());
		uploadPublicKey(exportKeysInLocal(keypair, publicKeyPath, privateKeyPath));
	}


	void manageNotification(Path path) {
		Notify notify = new Notify().download(path).decrypt(this.getPrivateKey());

		switch (notify.getTypeNotification()) {
			case USER_CREATED:
				System.out.println(" UserCreated!");
				if (this instanceof Admin) {
					((Admin) this).signUser(notify.getUserCreated().getUser());
					notify.getUser().localDelete();
					Main.success("UserCreatedPolling");
				} else {
					throw new IllegalStateException("You are not an Admin, operation not permitted.");
				}
				break;
			case GROUP_CREATED:
				System.out.println(" GroupCreated!");
				if (this instanceof Admin) {
					((Admin) this).signGroup(notify.getGroupCreated().getGroup());
					notify.getGroup().localDelete();

					Main.success("GroupCreatedPolling");
				} else {
					throw new IllegalStateException("You are not an Admin, operation not permitted.");
				}
				break;
			case GROUP_REMOVED:
				System.out.println(" GroupRemoved!");
				notify.getGroupRemoved();
				if (!Dropbox.existFile(Dropbox.GROUPS_COMPOSITION.resolve(notify.getGroup().getName()))) {
					if (this instanceof Admin) {
						((Admin) this).designGroup(notify.getGroup());

					} else {
						answerGroupRemoved(notify.getGroup(), notify.getUser(), notify.getPwdFolder());
					}
					Main.success("GroupRemovedPolling");
				} else {
					System.err.println("Group is still here, discarding notification");
				}
				break;
			case PWDFOLDER_SHARED:
				System.out.println(" PwdFolderShared!");
				notify.getPwdFolderShared();
				answerPwdFolderShared(notify.getPwdFolder(), notify.getGroup(), notify.getAccessLevel(), notify.getPassword());
				notify.getGroup().localDelete();
				notify.getPwdFolder().localDelete();
				Main.success("PwdSharedPolling");
				break;
			case PWDFOLDER_REMOVED:
				System.out.println(" PwdFolderRemoved!");
				notify.getPwdFolderRemoved();
				answerPwdFolderRemoved(notify.getGroup(), notify.getPwdFolder());
				notify.getGroup().localDelete();
				notify.getPwdFolder().localDelete();
				Main.success("PwdRemovedPolling");
				break;
			case USERS_ADDED_TO_GROUP:
				System.out.println(" UsersAdded!");
				notify.getUsersAddedOrRemoved();
				answerUsersAdded(notify.getPwdFolder(), notify.getGroup(), notify.getMembers());
				notify.getGroup().localDelete();
				notify.getPwdFolder().localDelete();
				notify.getMembers().forEach(User::localDelete);
				Main.success("UsersAddedPolling");
				break;
			case USERS_REMOVED_FROM_GROUP:
				System.out.println(" UsersRemoved!");
				notify.getUsersAddedOrRemoved();
				answerUsersRemoved(notify.getPwdFolder(), notify.getGroup(), notify.getMembers());
				Main.success("UsersRemovedPolling");
				break;
			default:
				System.out.println(" ,a wrong Notification!");
				System.out.println("Notification is discarded");

				break;

		}
		notify.localDelete();

	}

	void notificationsWhileAFK(Path path) {
		try {
			List<Metadata> notifications = new ArrayList<>();
			ListFolderResult result = Dropbox.getClient().files().listFolder(path.toString());
			while (true) {
				notifications.addAll(result.getEntries());
				if (!result.getHasMore()) {
					break;
				}
				result = Dropbox.getClient().files().listFolderContinue(result.getCursor());
			}
			notifications.forEach(notify -> {

				try {
					System.out.print("You received a notification");
					manageNotification(Paths.get(notify.getPathLower()));
					Dropbox.getClient().files().deleteV2(notify.getPathLower());
				} catch (DbxException e) {
					throw new Main.ExecutionException("notificationsWhileAFK", e, this);
				}
			});
		} catch (DbxException e) {
			throw new Main.ExecutionException("notificationsWhileAFK", e, this);
		}
	}


	private void answerGroupRemoved(Group group, User user, PwdFolder pwdFolder) {
		try {
			Optional<Pair<Group, AccessLevel>> groupAccessLevel = pwdFolder.getGroupsAccesses().stream().filter(pair ->
					pair.getValue0().equals(group)).findFirst();
			groupAccessLevel.ifPresent(pair ->
					pwdFolder.getGroupsAccesses().remove(pair));
			if (pwdFolder.checkIfRemove(group, user)) {
				Dropbox.removeUsersFromFolder(Dropbox.BASE.resolve(pwdFolder.getName()), Collections.singletonList(user));
			}
		} catch (DbxException e) {
			throw new Main.ExecutionException("designGroup", e, this);
		}
	}

	private void answerPwdFolderRemoved(Group group, PwdFolder pwdFolder) {
		try {
			FileSystem fileSystem = Vault.getPersonalVault().open();
			if (fileSystem != null) {
				Files.deleteIfExists(fileSystem.getPath(Vault.SLASH.resolve(group.getName()).resolve(pwdFolder.getName()).toString()));
				fileSystem.close();
			} else {
				throw new Main.ExecutionException("answerPwdFolderRemoved");
			}

		} catch (IOException e) {
			throw new Main.ExecutionException("answerPwdFolderRemoved", e, this);
		}

	}

	private void answerPwdFolderShared(PwdFolder pwdFolder, Group group, AccessLevel accessLevel, String password) {
		try {
			Dropbox.mountFolder(Paths.get(pwdFolder.getName()));
			FileSystem fileSystem = Vault.getPersonalVault().open();
			if (fileSystem != null) {
				Files.createDirectories(fileSystem.getPath(Vault.SLASH.resolve(group.getName()).toString()));
				JsonObject jsonObject = new JsonObject();
				jsonObject.add(Notify.TypeMemberName.OWNER.toString(), new JsonParser().parse(pwdFolder.getOwner().getEmail()));
				jsonObject.add(Notify.TypeMemberName.PWDFOLDER.toString(), new JsonParser().parse(pwdFolder.getName()));
				jsonObject.add(Notify.TypeMemberName.PASSWORD.toString(), new JsonParser().parse(password));
				jsonObject.add(Notify.TypeMemberName.ACCESSLEVEL.toString(), new JsonParser().parse(accessLevel.toString()));
				Files.write(fileSystem.getPath(Vault.SLASH.resolve(group.getName()).resolve(pwdFolder.getName()).toString()), jsonObject.toString().getBytes());
				fileSystem.close();
			} else {
				throw new Main.ExecutionException("answerPwdFolderShared");
			}
		} catch (DbxException | IOException e) {
			throw new Main.ExecutionException("answerPwdFolderShared", e, this);
		}

	}

	//TODO test TIPO UN BOTTO
	private void checkIfAddOrRemove(PwdFolder pwdFolder, Group group, List<User> userList, Notify.TypeNotification notification) {
		if (pwdFolder.getOwner().equals(this)) {
			if (group.getVerified()) {
				if (notification.equals(Notify.TypeNotification.USERS_ADDED_TO_GROUP)) {
					if (!group.getMembers().containsAll(userList)) {
						System.err.println("Users are not members of the group, impossible to share with them");
						return;
					}
				} else if (notification.equals(Notify.TypeNotification.USERS_REMOVED_FROM_GROUP)) {
					if (group.getMembers().containsAll(userList)) {
						System.err.println("Users are still members of the group, impossible to unshare them");
						return;
					}
				} else {
					throw new Main.ExecutionException("checkIfAddOrRemove");
				}
				if (userList.stream().allMatch(User::getVerified)) { //se tutti gli utenti sono verificati
					Optional<Pair<Group, AccessLevel>> pair = pwdFolder.getGroupsAccesses().stream().filter(pair2 ->
							pair2.getValue0().equals(group)).findFirst();
					if (pair.isPresent()) { //se il gruppo ha la condivisione del pwdfolder
						AccessLevel accessLevel = pair.get().getValue1();
						if (notification.equals(Notify.TypeNotification.USERS_ADDED_TO_GROUP)) {
							pwdFolder.shareUsers(group, accessLevel, userList);

						} else if (notification.equals(Notify.TypeNotification.USERS_REMOVED_FROM_GROUP)) {
							pwdFolder.unshareUsers(userList, group);

						} else {

							throw new Main.ExecutionException("answerUsersAdded");
						}

					} else {
						throw new Main.ExecutionException("answerUsersAdded");
					}

				} else {
					System.err.println("Users are not verified, operation not permitted");
				}
			} else {
				System.err.println("Group is not verified, operation not permitted");
			}
		} else {
			System.err.println("You are not the owner of the group, operation not permitted");
		}
	}

	//TODO test
	private void answerUsersRemoved(PwdFolder pwdFolder, Group group, List<User> exUsers) {
		checkIfAddOrRemove(pwdFolder, group, exUsers, Notify.TypeNotification.USERS_REMOVED_FROM_GROUP);
		Main.success("answerUsersRemoved");
	}

	//TODO test TIPO UN BOTTO
	private void answerUsersAdded(PwdFolder pwdFolder, Group group, List<User> newUsers) {
		checkIfAddOrRemove(pwdFolder, group, newUsers, Notify.TypeNotification.USERS_ADDED_TO_GROUP);
		Main.success("answerUsersAdded");
	}


	void createPwdFolder() {
		System.out.println("Enter a name for the PwdFolder");
		String name = Main.inputUser();
		while (Dropbox.existFile(Dropbox.BASE.resolve(name))) {
			System.err.println("Name already exists: please insert a new name");
			name = Main.inputUser();
		}
		System.out.println("Enter the name of a group you want to share with, press 'q' for stop");
		System.out.println("These are the Groups created");
		listGroups().forEach(group -> System.out.println(group.getName()));
		List<Pair<Group, AccessLevel>> pairList = addGroupsToList(new ArrayList<>());
		System.out.println("A random secure password is chosen for the PwdFolder");
		Vault vault = new Vault(Vault.BASE_PATH.resolve(name));
		vault.createPassword().create();
		PwdFolder pwdFolder = new PwdFolder.PwdFolderBuilder(name, this, vault, pairList).build();
		pwdFolder.upload(this).share(pairList).localDelete();
		Main.success("createPwdFolder");
	}

	void addGroupsToPwdFolder() {
		System.out.println("Enter the name you chose for the PwdFolder");
		System.out.println("These are the PwdFolder that you own");
		List<PwdFolder> pwdFolders = listPwdFolders();
		pwdFolders.forEach(System.out::println);
		String name = Main.inputUser();
		PwdFolder pwdFolder = new PwdFolder.PwdFolderBuilder(name).setFromDropbox().build();
		if (this.equals(pwdFolder.getOwner()) && pwdFolders.contains(pwdFolder)) {
			List<Group> canBeAdded = listGroups();
			List<Group> alreadyIn = new ArrayList<>();
			pwdFolder.getGroupsAccesses().forEach(pair -> alreadyIn.add(pair.getValue0()));
			canBeAdded.removeAll(alreadyIn);
			System.out.println("Please insert the Groups  you want to add, press 'q' to stop");
			System.out.println("These are the Groups that can be added");
			canBeAdded.forEach(System.out::println);
			List<Pair<Group, AccessLevel>> newGroupsAccessLevel = addGroupsToList(alreadyIn);
			pwdFolder.getGroupsAccesses().addAll(newGroupsAccessLevel);
			pwdFolder.upload(this).share(newGroupsAccessLevel).localDelete();
			Main.success("addGroupsToPwdFolder");
		} else {
			System.err.println("You are not owner, operation not permitted");
		}
	}

	private List<Pair<Group, AccessLevel>> addGroupsToList(List<Group> alreadyIn) {
		List<Pair<Group, AccessLevel>> pairs = new ArrayList<>();
		String groupName = Main.inputUser();
		while (!groupName.equals("q")) {
			if (Dropbox.existFile(Dropbox.GROUPS_COMPOSITION.resolve(groupName))) {
				Group group = new Group.GroupBuilder(groupName).setFromDropbox().build();
				if (group.getVerified()) {
					if (pairs.stream().noneMatch(pair -> pair.getValue0().equals(group))) {
						if (!alreadyIn.contains(group)) {
							System.out.println("Enter 'R' for read access, enter 'W' for write access");
							String accesslevel = Main.inputUser();
							while (!accesslevel.equals("R") && !accesslevel.equals("W")) {
								System.err.println("Please enter 'R' for read access, enter 'W' for write access");
								accesslevel = Main.inputUser();
							}
							switch (accesslevel) {
								case "R":
									pairs.add(new Pair<>(group, AccessLevel.VIEWER));
									break;
								case "W":
									pairs.add(new Pair<>(group, AccessLevel.EDITOR));
									break;
								default:
									throw new Main.ExecutionException("createPwdFolder");
							}
							System.out.println("Enter the name of a group you want to share with, press 'q' for stop");
						} else {
							System.err.println("Already shared with the group, select another one");
						}
					} else {
						System.err.println("Group already added, select another one");
					}

				} else {
					System.err.println("Group isn't verified, select another one");
				}

			} else {
				System.err.println("Group doesn't exist, select another one");
			}
			groupName = Main.inputUser();
		}
		return pairs;
	}


	void openPwdFolder() {
		System.out.println("Enter the name you chose for the PwdFolder");
		System.out.println("These are the PwdFolder that you own");
		List<PwdFolder> pwdFolders = listPwdFolders();
		pwdFolders.forEach(System.out::println);
		String name = Main.inputUser();
		PwdFolder pwdFolder = new PwdFolder.PwdFolderBuilder(name).setFromDropbox().build();
		if (pwdFolders.contains(pwdFolder)) {
			pwdFolder.open();
		} else {
			System.err.println("Operation not permitted");
		}
	}
	void removeGroupsFromPwdFolder() {
		System.out.println("Enter the name you chose for the PwdFolder");
		System.out.println("These are the PwdFolder that you own");
		List<PwdFolder> pwdFolders = listPwdFolders();
		pwdFolders.forEach(System.out::println);
		String name = Main.inputUser();
		PwdFolder pwdFolder = new PwdFolder.PwdFolderBuilder(name).setFromDropbox().build();
		if (this.equals(pwdFolder.getOwner()) && pwdFolders.contains(pwdFolder)) {
			List<Group> alreadyIn = new ArrayList<>();
			pwdFolder.getGroupsAccesses().forEach(pair -> alreadyIn.add(pair.getValue0()));
			System.out.println("Please insert the Groups  you want to remove, press 'q' to stop");
			System.out.println("These are the Groups that can be removed");
			alreadyIn.forEach(System.out::println);
			List<Group> toRemove = removeGroupsFromList(alreadyIn);
			List<Pair<Group, AccessLevel>> toRemoveGA = new ArrayList<>();
			pwdFolder.getGroupsAccesses().forEach(pair -> {
				if (toRemove.contains(pair.getValue0())) {
					toRemoveGA.add(pair);
				}
			});
			pwdFolder.getGroupsAccesses().removeAll(toRemoveGA);
			pwdFolder.upload(this).unshare(toRemove).localDelete();
			Main.success("removeGroupsFromPwdFolder");
		} else {
			System.err.println("You are not owner, operation not permitted");

		}
	}

	private List<Group> removeGroupsFromList(List<Group> alreadyIn) {
		List<Group> groups = new ArrayList<>();
		String groupName = Main.inputUser();
		while (!groupName.equals("q")) {
			if (Dropbox.existFile(Dropbox.GROUPS_COMPOSITION.resolve(groupName))) {
				Group group = new Group.GroupBuilder(groupName).setFromDropbox().build();
				if (group.getVerified()) {
					if (alreadyIn.contains(group)) {
						if (!groups.contains(group)) {
							groups.add(group);
							System.out.println("Please insert the Groups  you want to remove, press 'q' to stop");

						} else {
							System.err.println("Already selected, select another one");
						}
					} else {
						System.err.println("Wasn't shared with this group, select another one");
					}

				} else {
					System.err.println("Group isn't verified, select another one");
				}

			} else {
				System.err.println("Group doesn't exist, select another one");
			}
			groupName = Main.inputUser();
		}
		return groups;
	}

	void deletePwdFolder() {
		System.out.println("Enter the name you chose for the PwdFolder");
		System.out.println("These are the PwdFolder that you own");
		List<PwdFolder> pwdFolders = listPwdFolders();
		pwdFolders.forEach(System.out::println);
		String name = Main.inputUser();
		PwdFolder pwdFolder = new PwdFolder.PwdFolderBuilder(name).setFromDropbox().build();
		if (this.equals(pwdFolder.getOwner()) && pwdFolders.contains(pwdFolder)) {
			pwdFolder.delete();
		} else {
			System.err.println("Operation not permitted");
		}
	}


	void createGroup() {
		System.out.println("Enter a name for the Group");
		List<User> members;
		String name = Main.inputUser();
		while (Dropbox.existFile(Dropbox.GROUPS_COMPOSITION.resolve(name))) {
			System.err.println("Name already exists: please insert a new name");
			name = Main.inputUser();
		}
		System.out.println("Please insert the emails of the users you want to add, press 'q' to stop");
		System.out.println("These are the Users that can be added");
		List<User> users = listUsers();
		users.remove(this);
		users.forEach(System.out::println);
		members = insertUsersInList(Collections.singletonList(this));
		Group group = new Group.GroupBuilder(name, this, members).build();
		group.upload(this).localDelete();
	}

	//TODO TEST
	void addMembersToGroup() {
		System.out.println("Enter the name you chose for the Group");
		System.out.println("These are the Groups created");
		listGroups().forEach(System.out::println);
		String name = Main.inputUser();
		Group group = new Group.GroupBuilder(name).setFromDropbox().build();
		if (group.getVerified()) {
			if (this.equals(group.getOwner())) {
				List<User> canBeAdded = listUsers();
				canBeAdded.removeAll(group.getMembers());
				System.out.println("Please insert the emails of the users you want to add, press 'q' to stop");
				System.out.println("These are the Users that can be added");

				canBeAdded.forEach(System.out::println);

				List<User> newMembers = insertUsersInList(group.getMembers());
				group.getMembers().addAll(newMembers);
				group.upload(this).warnAdded(newMembers).localDelete();
			} else {
				System.err.println("You are not owner of the Group");

			}
		} else {
			System.err.println("The Group is not verified by an Admin");

		}

	}

	private List<User> insertUsersInList(List<User> in) {
		List<User> users = new ArrayList<>();
		String email = Main.inputUser();
		while (!email.equals("q")) {
			UserBuilder userBuilder = new UserBuilder(email);
			if (listUsersBuilder().contains(userBuilder)) {
				User user = userBuilder.setPublicKey().setVerified().build();
				user.localDelete();
				if (!users.contains(user)) {
					if (!in.contains(user)) {
						if (user.getVerified()) {
							users.add(user);
						} else {
							System.err.println("User has not been verified by an Admin, you can not choose him");
						}

					} else {
						System.err.println("User has already been added, you can not choose him");
					}
				} else {
					System.err.println("User has already been added, you can not choose him");

				}

			} else {
				System.err.println("User do not exists");

			}
			email = Main.inputUser();
		}
		return users;
	}


	void deleteGroup() {
		System.out.println("Enter the name you chose for the Group");
		System.out.println("These are the Groups created");
		listGroups().forEach(System.out::println);
		String name = Main.inputUser();
		Group group = new Group.GroupBuilder(name).setFromDropbox().build();
		if (group.getVerified()) {
			if (this.equals(group.getOwner())) {
				if (group.getMembers().isEmpty()) {
					group.delete().localDelete();
				} else {
					System.err.println("You need to remove every member before");

				}
			} else {
				System.err.println("You are not owner of the Group");
			}

		} else {
			System.err.println("The Group is not verified by an Admin");
		}

	}

	//TODO TEST
	void removeMembersFromGroup() {
		System.out.println("Enter the name you chose for the Group");
		System.out.println("These are the Groups created");
		listGroups().forEach(System.out::println);
		String name = Main.inputUser();
		Group group = new Group.GroupBuilder(name).setFromDropbox().build();
		if (group.getVerified()) {
			if (this.equals(group.getOwner())) {
				System.out.println("Please insert the emails of the users you want to remove, press 'q' to stop");
				group.getMembers().forEach(System.out::println);
				List<User> exMembers = removeUsersInList(group.getMembers());
				group.getMembers().removeAll(exMembers);
				group.upload(this).warnRemoved(exMembers).localDelete();
			} else {
				System.err.println("You are not owner of the Group");

			}

		} else {
			System.err.println("The Group is not verified by an Admin");

		}

	}

	private List<User> removeUsersInList(List<User> base) {
		List<User> users = new ArrayList<>();
		String email = Main.inputUser();
		while (!email.equals("q")) {
			UserBuilder userBuilder = new UserBuilder(email);
			if (listUsersBuilder().contains(userBuilder)) {
				User user = userBuilder.setPublicKey().setVerified().build();
				user.localDelete();
				if (base.contains(user)) {
					if (!users.contains(user)) {
						users.add(user);
					} else {
						System.err.println("User already selected, you can not choose him");
					}
				} else {
					System.err.println("User is not present, you can not choose him");

				}

			} else {
				System.err.println("User do not exists");

			}
			email = Main.inputUser();
		}
		return users;
	}


	List<PwdFolder> listPwdFolders() {
		try {
			List<PwdFolder> pwdFolders = new ArrayList<>();
			FileSystem fileSystem = Vault.getPersonalVault().open();
			if (fileSystem != null) {
				Files.list(fileSystem.getPath(Vault.MY_PWDFOLDER.toString())).forEach(path ->
						pwdFolders.add(new PwdFolder.PwdFolderBuilder(path.getFileName().toString()).build()));

				fileSystem.close();
			} else {
				throw new Main.ExecutionException("listPwdFolders");
			}
			return pwdFolders;
		} catch (IOException e) {
			throw new Main.ExecutionException("listPwdFolders", e, this);
		}
	}

	List<User> listUsers() {
		List<User> users = new ArrayList<>();
		listUsersBuilder().forEach(user -> users.add(user.build()));
		return users;
	}

	List<UserBuilder> listUsersBuilder() {
		try {
			List<UserBuilder> userStream = new ArrayList<>();
			Dropbox.getClient().sharing().listFolderMembers(Dropbox
					.getSharedFolderId(Dropbox.SYSTEM)).getUsers().forEach(s ->
					userStream.add(new UserBuilder(s.getUser().getEmail()))
			);
			return userStream;
		} catch (DbxException e) {
			throw new Main.ExecutionException("listUsersBuilder", e, this);
		}

	}

	List<Group> listGroups() {
		try {
			List<Group> groupList = new ArrayList<>();

			ListFolderResult result = Dropbox.getClient().files().listFolder(Dropbox.GROUPS_COMPOSITION.toString());
			while (true) {
				result.getEntries().forEach(metadata ->
						groupList.add(new Group.GroupBuilder(metadata.getName()).build()));
				if (!result.getHasMore()) {
					break;
				}
				result = Dropbox.getClient().files().listFolderContinue(result.getCursor());
			}
			return groupList;
		} catch (DbxException e) {
			throw new Main.ExecutionException("listGroups", e, this);
		}
	}


	void createFileSystem() {
		try {
			Dropbox.mountFolder(Dropbox.SYSTEM.getFileName());
			Dropbox.mountFolder(Dropbox.SIGNED_PUBLIC_KEYS.getFileName());
			Dropbox.mountFolder(Dropbox.SIGNED_GROUPS_OWNER.getFileName());
			Dropbox.createFolder(Dropbox.MESSAGE_PASSING.resolve(getEmail()), false);

		} catch (DbxException e) {
			throw new Main.ExecutionException("createFileSystem", e, this);
		}

	}

	void setup() {
		createFileSystem();
		uploadPublicKey(Main.MY_PERSONAL_PATH.resolve(getEmail() + Main.END_PUBLIC));
		Vault.initPersonalStorage(this);
		if (getVerified()) {
			notificationsWhileAFK(OWN_MESSAGE_PASSING);
			Polling polling = new Polling(this.OWN_MESSAGE_PASSING, this);
			polling.start();
			System.out.println("A thread is listening for new notifications");
		}
		Main.success("Caller.setup");
	}
}
