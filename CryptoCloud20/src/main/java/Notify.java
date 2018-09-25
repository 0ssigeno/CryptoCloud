import com.dropbox.core.DbxException;
import com.dropbox.core.v2.sharing.AccessLevel;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.crypto.Cipher;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

class Notify {
	private TypeNotification typeNotification;
	private User user;
	private Group group;
	private Path localPath;
	private byte[] content;
	private JsonObject jsonObject;
	private PwdFolder pwdFolder;
	private String password;
	private AccessLevel accessLevel;
	private List<User> members;

	List<User> getMembers() {
		if (members == null) {
			throw new IllegalStateException("Members not initialized.");
		}
		return members;
	}

	String getPassword() {
		if (password == null) {
			throw new IllegalStateException("Password not initialized.");
		}
		return password;
	}

	Notify(){

	}

	AccessLevel getAccessLevel() {
		if (accessLevel == null) {
			throw new IllegalStateException("AccessLevel not initialized.");
		}
		return accessLevel;
	}

	PwdFolder getPwdFolder() {
		if (pwdFolder == null) {
			throw new IllegalStateException("PwdFolder not initialized.");
		}
		return pwdFolder;
	}

	Group getGroup() {
		if (group == null) {
			throw new IllegalStateException("Group not initialized.");
		}
		return group;
	}

	User getUser() {
		if (user == null) {
			throw new IllegalStateException("User not initialized.");
		}
		return user;
	}
	TypeNotification getTypeNotification() {
		if (typeNotification == null) {
			throw new IllegalStateException("Type not initialized.");
		}
		return typeNotification;
	}

	//DOWNLOAD SIDE
	Notify download(Path uploaded) {
		try {
			localPath = Dropbox.download(uploaded.getParent(), uploaded.getFileName().toString(), "");
			//Dropbox.upload(localPath,pathUpload.resolve(localPath.getFileName()));

		} catch (IOException | DbxException e) {
			throw  new Main.ExecutionException("download",e,this);
		}
		return this;
	}

	Notify decrypt(PrivateKey privateKey) {
		if(localPath !=null){
			try {
				Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
				cipher.init(Cipher.DECRYPT_MODE, privateKey);
				content=cipher.doFinal(Files.readAllBytes(localPath));
				jsonObject = new JsonParser().parse(new String(content)).getAsJsonObject();
				typeNotification = Enum.valueOf(TypeNotification.class, jsonObject.get(TypeMemberName.TYPE.toString()).getAsString());
				return this;
			} catch (Exception e) {
				throw  new Main.ExecutionException("decrypt",e,this);
			}
		}else{
			throw new IllegalStateException("Path not initialized.");
		}
	}

	Notify getUserCreated() {
		String email = jsonObject.get(TypeMemberName.USER.toString()).getAsString();
		this.user = new User.UserBuilder(email).setPublicKey().build();
		return this;
	}

	Notify setUserCreated(Caller caller) {
		this.typeNotification = TypeNotification.USER_CREATED;
		this.user = caller;
		JsonObject jsonObject = new JsonObject();
		jsonObject.add(TypeMemberName.TYPE.toString(), new JsonParser().parse(typeNotification.toString()));
		jsonObject.add(TypeMemberName.USER.toString(), new JsonParser().parse(user.getEmail()));
		this.jsonObject = jsonObject;
		return this;
	}

	Notify getGroupCreated() {
		String groupName = jsonObject.get(TypeMemberName.GROUP.toString()).getAsString();
		this.group = new Group.GroupBuilder(groupName).setFromDropbox().build();
		return this;
	}

	void getPwdFolderShared() {
		this.accessLevel = AccessLevel.valueOf(jsonObject.get(TypeMemberName.ACCESSLEVEL.toString()).getAsString());
		String groupName = jsonObject.get(TypeMemberName.GROUP.toString()).getAsString();
		String pwdFolderName = jsonObject.get(TypeMemberName.PWDFOLDER.toString()).getAsString();
		this.group = new Group.GroupBuilder(groupName).build();
		this.pwdFolder = new PwdFolder.PwdFolderBuilder(pwdFolderName).setOwner(new User.UserBuilder(jsonObject.get(TypeMemberName.OWNER.toString()).getAsString()).build()).build();
		this.password = jsonObject.get(TypeMemberName.PASSWORD.toString()).getAsString();
	}

	//TODO test
	void getUsersAddedOrRemoved() {
		String groupName = jsonObject.get(TypeMemberName.GROUP.toString()).getAsString();
		String pwdFolderName = jsonObject.get(TypeMemberName.PWDFOLDER.toString()).getAsString();
		this.group = new Group.GroupBuilder(groupName).setFromDropbox().build();
		this.pwdFolder = new PwdFolder.PwdFolderBuilder(pwdFolderName).setFromDropbox().build();
		List<User> users = new ArrayList<>();
		jsonObject.getAsJsonArray(TypeMemberName.MEMBERS.toString())
				.forEach(name -> users.add(new User.UserBuilder(name.getAsString()).setPublicKey().setVerified().build()));
		this.members = users;
	}

	void getPwdFolderRemoved() {
		String groupName = jsonObject.get(TypeMemberName.GROUP.toString()).getAsString();
		String pwdFolderName = jsonObject.get(TypeMemberName.PWDFOLDER.toString()).getAsString();
		this.group = new Group.GroupBuilder(groupName).setFromDropbox().build();
		this.pwdFolder = new PwdFolder.PwdFolderBuilder(pwdFolderName).build();
	}

	void getGroupRemoved() {
		String groupName = jsonObject.get(TypeMemberName.GROUP.toString()).getAsString();
		this.group = new Group.GroupBuilder(groupName).build();
		this.user = new User.UserBuilder(jsonObject.get(TypeMemberName.OWNER.toString()).getAsString()).setPublicKey().setVerified().build();
		if (jsonObject.has(TypeMemberName.PWDFOLDER.toString())) {
			this.pwdFolder = new PwdFolder.PwdFolderBuilder(jsonObject.get(TypeMemberName.PWDFOLDER.toString()).getAsString()).setFromDropbox().build();
		}
	}

	//UPLOAD SIDE
	Notify setGroupRemoved(Group group, User owner, PwdFolder pwdFolder) {
		this.typeNotification = TypeNotification.GROUP_REMOVED;
		this.user = owner;
		this.group = group;
		this.pwdFolder = pwdFolder;
		JsonObject jsonObject = new JsonObject();
		jsonObject.add(TypeMemberName.TYPE.toString(), new JsonParser().parse(typeNotification.toString()));
		jsonObject.add(TypeMemberName.GROUP.toString(), new JsonParser().parse(group.getName()));
		jsonObject.add(TypeMemberName.OWNER.toString(), new JsonParser().parse(owner.getEmail()));
		if (pwdFolder != null) {
			jsonObject.add(TypeMemberName.PWDFOLDER.toString(), new JsonParser().parse(pwdFolder.getName()));
		}

		this.jsonObject = jsonObject;
		return this;
	}

	Notify setPwdFolderRemoved(Group group, PwdFolder pwdFolder) {
		this.typeNotification = TypeNotification.PWDFOLDER_REMOVED;
		this.group = group;
		this.pwdFolder = pwdFolder;
		JsonObject jsonObject = new JsonObject();
		jsonObject.add(TypeMemberName.TYPE.toString(), new JsonParser().parse(typeNotification.toString()));
		jsonObject.add(TypeMemberName.GROUP.toString(), new JsonParser().parse(group.getName()));
		jsonObject.add(TypeMemberName.PWDFOLDER.toString(), new JsonParser().parse(pwdFolder.getName()));
		this.jsonObject = jsonObject;
		return this;
	}

	//TODO test
	Notify setUsersAddedOrRemoved(Group group, PwdFolder pwdFolder, List<User> userList, TypeNotification typeNotification) {
		this.typeNotification = typeNotification;
		this.group = group;
		this.pwdFolder = pwdFolder;
		JsonObject jsonObject = new JsonObject();
		jsonObject.add(TypeMemberName.TYPE.toString(), new JsonParser().parse(typeNotification.toString()));
		jsonObject.add(TypeMemberName.GROUP.toString(), new JsonParser().parse(group.getName()));
		jsonObject.add(TypeMemberName.PWDFOLDER.toString(), new JsonParser().parse(pwdFolder.getName()));
		jsonObject.add(TypeMemberName.MEMBERS.toString(), new JsonParser().parse(userList.toString()));
		this.jsonObject = jsonObject;
		return this;
	}

	Notify setPwdFolderShared(PwdFolder pwdFolder, Group group, AccessLevel accessLevel) {
		this.typeNotification = TypeNotification.PWDFOLDER_SHARED;
		this.group = group;
		this.pwdFolder = pwdFolder;
		JsonObject jsonObject = new JsonObject();
		jsonObject.add(TypeMemberName.TYPE.toString(), new JsonParser().parse(typeNotification.toString()));
		jsonObject.add(TypeMemberName.GROUP.toString(), new JsonParser().parse(group.getName()));
		jsonObject.add(TypeMemberName.ACCESSLEVEL.toString(), new JsonParser().parse(accessLevel.toString()));
		jsonObject.add(TypeMemberName.PWDFOLDER.toString(), new JsonParser().parse(pwdFolder.getName()));
		jsonObject.add(TypeMemberName.PASSWORD.toString(), new JsonParser().parse(pwdFolder.getVault().getPassword()));
		jsonObject.add(TypeMemberName.OWNER.toString(), new JsonParser().parse(pwdFolder.getOwner().getEmail()));
		this.jsonObject = jsonObject;
		return this;
	}

	Notify setGroupCreated(Caller caller, Group group) {
		this.typeNotification = TypeNotification.GROUP_CREATED;
		this.user = caller;
		this.group = group;
		JsonObject jsonObject = new JsonObject();
		jsonObject.add(TypeMemberName.TYPE.toString(), new JsonParser().parse(typeNotification.toString()));
		jsonObject.add(TypeMemberName.OWNER.toString(), new JsonParser().parse(user.getEmail()));
		jsonObject.add(TypeMemberName.GROUP.toString(), new JsonParser().parse(this.group.getName()));

		this.jsonObject = jsonObject;
		return this;
	}

	Notify encrypt(PublicKey publicKey) {
		if (jsonObject != null) {
			try {
				Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
				cipher.init(Cipher.ENCRYPT_MODE, publicKey);
				content = cipher.doFinal(jsonObject.toString().getBytes());
				return this;
			} catch (Exception e) {
				throw  new Main.ExecutionException("encrypt",e,this);
			}
		}else{
			throw new IllegalStateException("JsonObject not initialized.");
		}

	}

	Notify upload(Path path) {
		try {
			String randomName=UUID.randomUUID().toString();
			localPath=Files.write(Main.MY_TEMP_PATH.resolve(randomName),content);
			Dropbox.upload(localPath, path.resolve(localPath.getFileName()));
		} catch (IOException | DbxException e) {
			throw  new Main.ExecutionException("upload",e,this);
		}
		return this;
	}

	void localDelete() {
		Main.deleteLocalFiles(localPath);
	}

	public enum TypeNotification {
		USER_CREATED,
		GROUP_CREATED,
		GROUP_REMOVED,
		PWDFOLDER_SHARED,
		PWDFOLDER_REMOVED,
		USERS_ADDED_TO_GROUP,
		USERS_REMOVED_FROM_GROUP
	}


	public enum TypeMemberName {
		TYPE,
		USER,
		OWNER,
		GROUP,
		MEMBERS,
		PWDFOLDER,
		ACCESSLEVEL,
		GROUPSACCESSES,
		PASSWORD
	}

}
