import com.dropbox.core.DbxException;
import com.dropbox.core.v2.sharing.AccessLevel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class Admin extends Caller {

	private final Path OWN_MESSAGE_PASSING = Dropbox.MESSAGE_PASSING.resolve("admin");

	Admin(Caller caller) {
		super(new UserBuilder(caller.getEmail()).setCaller());
	}


	private void uploadPublicKey(Path publicKeyPath) {
		Path path = Dropbox.SIGNED_PUBLIC_KEYS.resolve("admin" + Main.END_ADMIN);
		if (!Dropbox.existFile(path)) {
			try {
				Dropbox.upload(publicKeyPath, path);
			} catch (IOException | DbxException e) {
				throw new Main.ExecutionException("uploadPublicKey", e, this);

			}
		}


	}


	//todo test
	void addUsersToFileSystem() {
		List<User> users = new ArrayList<>();
		System.out.println("Please enter emails of users to add");
		System.out.println("press 'q' to stop adding users");
		String userEmail = Main.inputUser();
		while (!userEmail.equals("q")) {
			users.add(new UserBuilder(userEmail).build());
			userEmail = Main.inputUser();
		}
		addUsersToFileSystem(users);
	}

	//todo test
	void removeUsersFromFileSystem() {
		List<User> users = new ArrayList<>();
		System.out.println("These are the users already in:");
		listUsers().forEach(System.out::println);
		System.out.println("Please enter emails of users to remove");
		System.out.println("press 'q' to stop remove users");
		String userEmail = Main.inputUser();
		List<UserBuilder> userBuilders = listUsersBuilder();
		UserBuilder userBuilder = new UserBuilder(userEmail);
		while (!userEmail.equals("q")) {
			if (userBuilders.contains(userBuilder)) {
				users.add(userBuilder.build());
			}
			userEmail = Main.inputUser();
			userBuilder = new UserBuilder(userEmail);
		}
		removeUsersFromFileSystem(users);
	}

	private void addUsersToFileSystem(List<User> users) {
		try {
			Dropbox.addUsersToFolder(AccessLevel.EDITOR, Dropbox.SYSTEM, users);
			Dropbox.addUsersToFolder(AccessLevel.VIEWER, Dropbox.SIGNED_PUBLIC_KEYS, users);
			Dropbox.addUsersToFolder(AccessLevel.VIEWER, Dropbox.SIGNED_GROUPS_OWNER, users);
		} catch (DbxException e) {
			throw new Main.ExecutionException("addUsersToFileSystem", e, this);

		}

	}

	private void removeUsersFromFileSystem(List<User> users) {
		try {
			Dropbox.removeUsersFromFolder(Dropbox.SYSTEM, users);
			Dropbox.removeUsersFromFolder(Dropbox.SIGNED_PUBLIC_KEYS, users);
			Dropbox.removeUsersFromFolder(Dropbox.SIGNED_GROUPS_OWNER, users);
			users.forEach(this::designUser);
			users.forEach(User::removePublicKey);
		} catch (DbxException e) {
			throw new Main.ExecutionException("removeUsersFromFileSystem", e, this);

		}
	}


	void designGroup() {
		System.out.println("These are the Groups that have a signature");
		List<Group> groups = listSignedGroups();
		groups.forEach(System.out::println);
		System.out.println("Please enter the Group you want to remove the signature");
		String input = Main.inputUser();
		Group group = new Group.GroupBuilder(input).build();
		while (!groups.contains(group)) {
			System.err.println("Please enter a valid Group");
			input = Main.inputUser();
			group = new Group.GroupBuilder(input).build();
		}
		designGroup(group);
	}

	void designGroup(Group group) {
		try {
			Dropbox.getClient().files().deleteV2(Dropbox.SIGNED_GROUPS_OWNER.resolve(group.getName() + Main.END_ADMIN).toString());
		} catch (DbxException e) {
			throw new Main.ExecutionException("designGroup", e, this);
		}
	}


	void signGroup() {
		System.out.println("These are the Groups without a signature");
		List<Group> groups = listGroups();
		groups.removeAll(listSignedGroups());
		groups.forEach(System.out::println);
		System.out.println("Please enter the Group you want to sign");
		String input = Main.inputUser();
		Group group = new Group.GroupBuilder(input).setFromDropbox().build();
		while (!groups.contains(group)) {
			System.err.println("Please enter a valid Group");
			input = Main.inputUser();
			group = new Group.GroupBuilder(input).setFromDropbox().build();
		}
		signGroup(group);
		Main.success("signGroup");
	}

	void signGroup(Group group) {
		System.out.println("Are you sure you want to sign the group " + group);
		System.out.println("That has as owner" + group.getOwner() + " ?");
		System.out.println("Press 'Y' for confirmation");
		if (Main.inputUser().equals("Y")) {
			try {
				byte[] sign = generatePkcs1Signature(getPrivateKey(), group.getOwner().getPublicKey().getEncoded());
				Path pathSign = Files.write(Main.MY_TEMP_PATH.resolve(group.getName() + Main.END_ADMIN), sign);
				Dropbox.upload(pathSign, Dropbox.SIGNED_GROUPS_OWNER.resolve(pathSign.getFileName()));
				Main.deleteLocalFiles(pathSign);
				group.localDelete();

			} catch (IOException | DbxException e) {
				throw new Main.ExecutionException("signUser", e, this);

			}
		}
	}


	void designUser() {
		System.out.println("These are the users that have a signature");
		List<User> users = listSignedUsers();
		users.forEach(System.out::println);
		System.out.println("Please enter the user you want to remove the signature");
		String input = Main.inputUser();
		User user = new UserBuilder(input).build();
		while (!users.contains(user)) {
			System.err.println("Please enter a valid User");
			input = Main.inputUser();
			user = new UserBuilder(input).build();
		}
		designUser(user);
	}

	private void designUser(User user) {
		try {
			Dropbox.getClient().files().deleteV2(Dropbox.SIGNED_PUBLIC_KEYS.resolve(user.getEmail() + Main.END_SIGNED).toString());
			//Dropbox.getClient().files().deleteV2(Dropbox.PUBLIC_KEYS.resolve(user.getEmail() + Main.END_PUBLIC).toString());

		} catch (DbxException e) {
			throw new Main.ExecutionException("designUser", e, this);
		}

	}


	void signUser() {
		System.out.println("These are the users waiting for a signature");
		List<User> users = listUsers();
		List<UserBuilder> usersBuilders = listUsersBuilder();
		users.removeAll(listSignedUsers());
		users.forEach(System.out::println);
		System.out.println("Please enter the user you want to Sign");
		String input = Main.inputUser();
		UserBuilder userBuilder = new UserBuilder(input);
		while (!usersBuilders.contains(userBuilder)) {
			System.err.println("Please enter a valid User");
			input = Main.inputUser();
			userBuilder = new UserBuilder(input);
		}
		signUser(userBuilder.setPublicKey().build());
	}

	void signUser(User user) { //user ha il parametro publickey settato
		System.out.println("Are you sure you want to sign the user " + user);
		System.out.println("That has PublicKey " + user.getPublicKey() + " ?");
		System.out.println("Press 'Y' for confirmation");
		if (Main.inputUser().equals("Y")) {
			try {
				byte[] sign = generatePkcs1Signature(getPrivateKey(), user.getPublicKey().getEncoded());

				Path pathSign = Files.write(Main.MY_TEMP_PATH.resolve(user.getEmail() + Main.END_SIGNED), sign);
				Dropbox.upload(pathSign, Dropbox.SIGNED_PUBLIC_KEYS.resolve(pathSign.getFileName()));
				Main.deleteLocalFiles(pathSign);
				user.localDelete();

			} catch (IOException | DbxException e) {
				throw new Main.ExecutionException("signUser", e, this);

			}

		} else {
			System.out.println("Signature canceled");
		}

	}


	private List<Group> listSignedGroups() {
		try {
			List<Group> signed = new ArrayList<>();
			Dropbox.getClient().files().listFolder(Dropbox.SIGNED_GROUPS_OWNER.toString()).getEntries().forEach(metadata -> {
				if (metadata.getName().endsWith(Main.END_ADMIN)) {
					signed.add(new Group.GroupBuilder(metadata.getName().replace(Main.END_ADMIN, "")).build());
				}
			});
			return signed;
		} catch (DbxException e) {
			throw new Main.ExecutionException("listSignedGroups", e, this);
		}
	}

	private List<User> listSignedUsers() {
		try {
			List<User> signed = new ArrayList<>();
			Dropbox.getClient().files().listFolder(Dropbox.SIGNED_PUBLIC_KEYS.toString()).getEntries().forEach(metadata -> {
				if (metadata.getName().endsWith(Main.END_SIGNED)) {
					signed.add(new UserBuilder(metadata.getName().replace(Main.END_SIGNED, "")).build());
				}
			});
			return signed;
		} catch (DbxException e) {
			throw new Main.ExecutionException("listSignedUsers", e, this);
		}
	}


	public void setup() {
		if (!Dropbox.existFile(Dropbox.SYSTEM)) {
			createFileSystem();

		}

		uploadPublicKey(Main.MY_PERSONAL_PATH.resolve(getEmail() + Main.END_PUBLIC));

		notificationsWhileAFK(OWN_MESSAGE_PASSING);
		Polling polling = new Polling(OWN_MESSAGE_PASSING, this);
		polling.start();
		System.out.println("A thread is listening for new notifications");
		Main.success("Admin.setup");
	}

	void createFileSystem() {

		Dropbox.createFolder(Dropbox.SYSTEM, true);

		Dropbox.createFolder(Dropbox.PUBLIC_KEYS, false);

		Dropbox.createFolder(Dropbox.MESSAGE_PASSING, false);

		Dropbox.createFolder(Dropbox.GROUPS_COMPOSITION, false);

		Dropbox.createFolder(Dropbox.SIGNED_GROUPS, false);

		Dropbox.createFolder(Dropbox.SIGNED_PUBLIC_KEYS, true);

		Dropbox.createFolder(Dropbox.SIGNED_GROUPS_OWNER, true);

		Dropbox.createFolder(Dropbox.MESSAGE_PASSING.resolve("admin"), false);

		addUsersToFileSystem();
	}


}
