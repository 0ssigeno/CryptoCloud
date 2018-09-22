import com.dropbox.core.DbxException;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import javax.crypto.Cipher;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.UUID;

public class Notify {
	public static enum Type {
		USER_CREATED,
		GROUP_CREATED,
		GROUP_REMOVED,
		PWDFOLDER_SHARED,
		PWDFOLDER_REMOVED,
		USERS_ADDED_TO_GROUP,
		USERS_REMOVED_TO_GROUP;
	}
	private Type type;
	private Path localPath;
	private byte[] content;
	private JsonObject jsonObject;

	Notify(){

	}

	Notify(JsonObject jsonObject){
		this.jsonObject = jsonObject;
		this.content=jsonObject.toString().getBytes();
	}

	public JsonObject getJsonObject() {
		if (jsonObject == null) {
			throw new IllegalStateException("JsonObject not initialized.");
		}
		return jsonObject;
	}


	public Path getLocalPath() {
		if (localPath == null) {
			throw new IllegalStateException("LocalPath not initialized.");
		}
		return localPath;
	}

	public byte[] getContent() {
		if (content == null) {
			throw new IllegalStateException("Content not initialized.");
		}
		return content;
	}

	public Type getType() {
		if (type == null) {
			throw new IllegalStateException("Type not initialized.");
		}
		return type;
	}

	private JsonObject toJson(){
		if(content !=null) {
			JsonParser parser = new JsonParser();
			return parser.parse(new String(content)).getAsJsonObject();
		}else{
			throw new IllegalStateException("Content not initialized.");

		}
	}

	public Notify download(Path uploaded){
		try {
			localPath=Dropbox.download(uploaded.getParent(),uploaded.getFileName().toString(),"");
			//Dropbox.upload(localPath,pathUpload.resolve(localPath.getFileName()));

		} catch (IOException | DbxException e) {
			throw  new Main.ExecutionException("download",e,this);
		}
		return this;
	}

	public Notify decrypt(PrivateKey privateKey) {
		if(localPath !=null){
			try {
				Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
				cipher.init(Cipher.DECRYPT_MODE, privateKey);
				content=cipher.doFinal(Files.readAllBytes(localPath));
				jsonObject=toJson();
				type=Enum.valueOf(Type.class,jsonObject.get("type").getAsString());
				return this;
			} catch (Exception e) {
				throw  new Main.ExecutionException("decrypt",e,this);
			}
		}else{
			throw new IllegalStateException("Path not initialized.");
		}
	}

	public Notify encrypt(PublicKey publicKey){
		if(content !=null){
			try {
				Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
				cipher.init(Cipher.ENCRYPT_MODE, publicKey);
				content= cipher.doFinal(content);
				return this;
			} catch (Exception e) {
				throw  new Main.ExecutionException("encrypt",e,this);
			}
		}else{
			throw new IllegalStateException("JsonObject not initialized.");
		}

	}

	public Notify upload(Path pathUpload){

		try {
			String randomName=UUID.randomUUID().toString();
			localPath=Files.write(Main.MY_TEMP_PATH.resolve(randomName),content);
			Dropbox.upload(localPath,pathUpload.resolve(localPath.getFileName()));
		} catch (IOException | DbxException e) {
			throw  new Main.ExecutionException("upload",e,this);
		}
		return this;
	}

	public void localDelete(){
		Main.deleteLocalFiles(localPath);

	}

}
