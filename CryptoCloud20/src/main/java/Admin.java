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

	void addUsersToFileSystem(List<User> users) {
		try {
			Dropbox.addUsersToFolder(AccessLevel.EDITOR, Dropbox.SYSTEM, users);
			Dropbox.addUsersToFolder(AccessLevel.VIEWER, Dropbox.SIGNED_PUBLIC_KEYS, users);
			Dropbox.addUsersToFolder(AccessLevel.VIEWER, Dropbox.SIGNED_GROUPS_OWNER, users);
		} catch (DbxException e) {
			throw new Main.ExecutionException("addUsersToFileSystem", e, this);

		}

	}

	void removeUsersToFileSystem(List<User> users) {
		try {
			Dropbox.removeUsersFromFolder(Dropbox.SYSTEM, users);
			Dropbox.removeUsersFromFolder(Dropbox.SIGNED_PUBLIC_KEYS, users);
			Dropbox.removeUsersFromFolder(Dropbox.SIGNED_GROUPS_OWNER, users);
			users.forEach(this::designUser);
		} catch (DbxException e) {
			throw new Main.ExecutionException("removeUsersToFileSystem", e, this);

		}
	}

	private void createFileSystem() {
		List<User> users = new ArrayList<>();
		System.out.println("Please enter emails of users to add");
		System.out.println("press 'q' to stop adding users");
		String userEmail = Main.inputUser();
		while (!userEmail.equals("q")) {
			users.add(new UserBuilder(userEmail).build());
			userEmail = Main.inputUser();
		}
		Dropbox.createFolder(Dropbox.SYSTEM, true);

		Dropbox.createFolder(Dropbox.PUBLIC_KEYS, false);

		Dropbox.createFolder(Dropbox.MESSAGE_PASSING, false);

		Dropbox.createFolder(Dropbox.GROUPS_COMPOSITION, false);

		Dropbox.createFolder(Dropbox.SIGNED_GROUPS, false);

		Dropbox.createFolder(Dropbox.SIGNED_PUBLIC_KEYS, true);

		Dropbox.createFolder(Dropbox.SIGNED_GROUPS_OWNER, true);

		Dropbox.createFolder(Dropbox.MESSAGE_PASSING.resolve("admin"), false);


		uploadPublicKey(Main.MY_PERSONAL_PATH.resolve(getEmail() + Main.END_PUBLIC));
		addUsersToFileSystem(users);
	}

	void signGroup(Group group) {
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

	void signUser(User user) { //user ha il parametro publickey settato

		try {
			byte[] sign = generatePkcs1Signature(getPrivateKey(), user.getPublicKey().getEncoded());

			Path pathSign = Files.write(Main.MY_TEMP_PATH.resolve(user.getEmail() + Main.END_SIGNED), sign);
			Dropbox.upload(pathSign, Dropbox.SIGNED_PUBLIC_KEYS.resolve(pathSign.getFileName()));
			Main.deleteLocalFiles(pathSign);
			user.localDelete();

		} catch (IOException | DbxException e) {
			throw new Main.ExecutionException("signUser", e, this);

		}


	}

	void answerGroupRemoved(Group group) {
		try {
			Dropbox.getClient().files().deleteV2(Dropbox.SIGNED_GROUPS_OWNER.resolve(group.getName() + Main.END_ADMIN).toString());
		} catch (DbxException e) {
			throw new Main.ExecutionException("answerGroupRemoved", e, this);
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

	void designUser(User user) {
		try {
			Dropbox.getClient().files().deleteV2(Dropbox.SIGNED_PUBLIC_KEYS.resolve(user.getEmail() + Main.END_SIGNED).toString());
			Dropbox.getClient().files().deleteV2(Dropbox.PUBLIC_KEYS.resolve(user.getEmail() + Main.END_PUBLIC).toString());

		} catch (DbxException e) {
			throw new Main.ExecutionException("designUser", e, this);
		}

	}

}
