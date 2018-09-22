import com.dropbox.core.*;
import com.dropbox.core.http.StandardHttpRequestor;
import com.dropbox.core.v2.DbxClientV2;
import com.dropbox.core.v2.files.*;
import com.dropbox.core.v2.sharing.AccessLevel;
import com.dropbox.core.v2.sharing.AddMember;
import com.dropbox.core.v2.sharing.MemberSelector;
import com.dropbox.core.v2.sharing.SharedFolderMetadata;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class DropboxClient {
	public static final long CHUNKED_UPLOAD_CHUNK_SIZE = 8L << 20; // 8MiB
	public static final int CHUNKED_UPLOAD_MAX_ATTEMPTS = 5;

	public final static Path SYSTEM = Paths.get("/System");
	public final static Path PUBLIC_KEYS = SYSTEM.resolve("PublicKeys");
	public final static Path SIGNED_GROUPS = SYSTEM.resolve("SignedGroups");
	public final static Path GROUPS_COMPOSITION = SYSTEM .resolve("GroupsComposition");
	public final static Path MESSAGE_PASSING = SYSTEM.resolve("MessagePassing");
	public final static Path SIGNED_PUBLIC_KEYS = Paths.get("/SignedKeys");
	public final static Path SIGNED_GROUPS_OWNER = Paths.get("/SignedGroupsOwner");
	public final static Path PERSONAL_FOLDER = Paths.get("/PersonalFolder");

	private static String callerEmail;
	private static DbxClientV2 client;
	private static DbxClientV2 longpollClient;

	public static String getCallerEmail() {
		if (callerEmail == null) {
			try {
				callerEmail=client.users().getCurrentAccount().getEmail();
			} catch (DbxException e) {
				throw new IllegalStateException("Email not initialized.");
			}
		}
		return callerEmail;
	}

	/**
	 * Create a DbxClientV2: if is the first time that the user authenticate himself,
	 * he will be redirect on the dropbox web page for the authorization.
	 * After the authorization the token will be saved in DropBoxToken.json
	 * If he already configured his account, the token will be only withdrawn.
	 */
	public static void  init()  {
		if (client == null) {
			final String KEY_INFO = "kh0d9rkompntepp";
			final String SECRET_INFO = "vsua8l868wd0xtn";
			Path url = Main.MY_PERSONAL_PATH.resolve("DropBoxToken.json").toAbsolutePath();
			DbxRequestConfig requestConfig = new DbxRequestConfig("CryptoClouds");

			DbxAuthInfo authInfo = null;
			File file = new File(url.toString());
			if (file.exists()) {
				try {
					authInfo = DbxAuthInfo.Reader.readFromFile(url.toString());
					client = new DbxClientV2(requestConfig, authInfo.getAccessToken(), authInfo.getHost());

				} catch (Exception e) {
					System.out.println("You are not authenticated,redirecting ");

				}
			} else {
				DbxAppInfo appInfo = new DbxAppInfo(KEY_INFO, SECRET_INFO);
				DbxWebAuth webAuth = new DbxWebAuth(requestConfig, appInfo);
				DbxWebAuth.Request webAuthRequest = DbxWebAuth.newRequestBuilder()
						.withNoRedirect()
						.build();

				String authorizeUrl = webAuth.authorize(webAuthRequest);
				System.out.println("1. Go to " + authorizeUrl);
				System.out.println("2. Click \"Allow\" (you might have to log in first).");
				System.out.println("3. Copy the authorization code.");
				System.out.print("Enter the authorization code here: ");
				String code = Main.inputUser();

				DbxAuthFinish authFinish;

				try {
					authFinish = webAuth.finishFromCode(code);
				} catch (DbxException ex) {
					System.err.println("Error in DbxWebAuth.authorize: " + ex.getMessage());
					System.exit(1);
					return;
				}

				System.out.println("Authorization complete.");
				System.out.println("- User ID: " + authFinish.getUserId());
				System.out.println("- Account ID: " + authFinish.getAccountId());
				System.out.println("- Access Token: " + authFinish.getAccessToken());


				client = new DbxClientV2(requestConfig, authFinish.getAccessToken());
				// Save auth information to output file.
				authInfo = new DbxAuthInfo(authFinish.getAccessToken(), appInfo.getHost());
				try {
					File output = new File(url.toString());
					//noinspection ResultOfMethodCallIgnored
					output.getParentFile().mkdirs();
					DbxAuthInfo.Writer.writeToFile(authInfo, output);
					System.out.println("Saved authorization information to \"" + output.getCanonicalPath() + "\".");
				} catch (IOException ex) {
					System.err.println("Error saving to <auth-file-out>: " + ex.getMessage());
					System.err.println("Dumping to stderr instead:");
					//DbxAuthInfo.Writer.writeToStream(authInfo, System.err);
					System.exit(1);
				}
			}
			StandardHttpRequestor.Config config = StandardHttpRequestor.Config.DEFAULT_INSTANCE;
			StandardHttpRequestor.Config longpollConfig = config.copy()
					.withReadTimeout(5, TimeUnit.MINUTES)
					.build();
			StandardHttpRequestor requestor = new StandardHttpRequestor(longpollConfig);
			DbxRequestConfig requestConfigLong = DbxRequestConfig.newBuilder("CryptoClouds longpoll")
					.withHttpRequestor(requestor)
					.build();
			assert authInfo != null;
			longpollClient = new DbxClientV2(requestConfigLong, authInfo.getAccessToken(), authInfo.getHost());
			Main.success("initDropboxClient");
		}
	}
	public static File download(Path initialPath, String nameFile, String extension) throws IOException, DbxException {

		File localFile = new File(Main.MY_TEMP_PATH.resolve(nameFile+extension).toString());
		if (!localFile.exists()) {
			//noinspection ResultOfMethodCallIgnored
			localFile.getParentFile().mkdirs();
		}
		FileOutputStream out = new FileOutputStream(localFile);
		DropboxClient.getClient().files().downloadBuilder(initialPath.resolve(nameFile+extension).toString()).download(out);
		return localFile;
	}


	public static void upload(Path localPath, Path uploadedPath) throws IOException, DbxException {

		long size = Files.size(localPath);

		if (size >= CHUNKED_UPLOAD_CHUNK_SIZE) {
			System.err.println("File too big, uploading the file in chunks.");
			//chunkedPersonalUploadFile(localFile, dropboxPathNoName); TODO
		} else {
			InputStream in = Files.newInputStream(localPath);
			client.files().uploadBuilder(uploadedPath.toString())
					.withMode(WriteMode.OVERWRITE)
					.uploadAndFinish(in);

		}

	}
	public static Boolean existFile(Path path){
		try{
			DropboxClient.getClient().files().getMetadata(path.toString());
			return true;
		}catch (DbxException e){
			return false;
		}

	}
	/**
	 * @return a DbxClientV2 client already initialized
	 */
	public static DbxClientV2 getClient() {
		if (client == null) {
			throw new IllegalStateException("Client not initialized.");
		}
		return client;
	}

	/**
	 * @return a DbxClientV2 client already initialized used for polling
	 */
	static DbxClientV2 getLongpollClient() {
		if (longpollClient == null) {
			throw new IllegalStateException("LongpollClient not initialized.");
		}
		return longpollClient;
	}

	/*
	private static void chunkedPersonalUploadFile(File localFile, String dropboxPath) {
		long size = localFile.length();


		long uploaded = 0L;
		DbxException thrown = null;

		// Chunked uploads have 3 phases, each of which can accept uploaded bytes:
		//
		//    (1)  Start: initiate the upload and get an upload session ID
		//    (2) Append: upload chunks of the file to append to our session
		//    (3) Finish: commit the upload and close the session
		//
		// We track how many bytes we uploaded to determine which phase we should be in.
		String sessionId = null;
		for (int i = 0; i < ProjectHandler.CHUNKED_UPLOAD_MAX_ATTEMPTS; ++i) {
			if (i > 0) {
				System.out.printf("Retrying chunked upload (%d / %d attempts)\n", i + 1, ProjectHandler.CHUNKED_UPLOAD_MAX_ATTEMPTS);
			}

			try (InputStream in = new FileInputStream(localFile)) {
				// if this is a retry, make sure seek to the correct offset
				//noinspection ResultOfMethodCallIgnored
				in.skip(uploaded);

				// (1) Start
				if (sessionId == null) {
					sessionId = client.files().uploadSessionStart()
							.uploadAndFinish(in, ProjectHandler.CHUNKED_UPLOAD_CHUNK_SIZE)
							.getSessionId();
					uploaded += ProjectHandler.CHUNKED_UPLOAD_CHUNK_SIZE;
					__printProgress(uploaded, size);
				}

				UploadSessionCursor cursor = new UploadSessionCursor(sessionId, uploaded);

				// (2) Append
				while ((size - uploaded) > ProjectHandler.CHUNKED_UPLOAD_CHUNK_SIZE) {
					client.files().uploadSessionAppendV2(cursor)
							.uploadAndFinish(in, ProjectHandler.CHUNKED_UPLOAD_CHUNK_SIZE);
					uploaded += ProjectHandler.CHUNKED_UPLOAD_CHUNK_SIZE;
					__printProgress(uploaded, size);
					cursor = new UploadSessionCursor(sessionId, uploaded);
				}

				// (3) Finish
				long remaining = size - uploaded;
				CommitInfo commitInfo = CommitInfo.newBuilder(dropboxPath)
						.withMode(WriteMode.ADD)
						.withClientModified(new Date(localFile.lastModified()))
						.build();
				FileMetadata metadata = client.files().uploadSessionFinish(cursor, commitInfo)
						.uploadAndFinish(in, remaining);

				System.out.println(metadata.toStringMultiline());
				return;
			} catch (RetryException ex) {
				thrown = ex;
				// RetryExceptions are never automatically retried by the client for uploads. Must
				// catch this exception even if DbxRequestConfig.getMaxRetries() > 0.
				__sleepQuietly(ex.getBackoffMillis());
			} catch (NetworkIOException ex) {
				thrown = ex;
				// network issue with Dropbox (maybe a timeout?) try again

			} catch (UploadSessionLookupErrorException ex) {
				if (ex.errorValue.isIncorrectOffset()) {
					thrown = ex;
					// server offset into the stream doesn't match our offset (uploaded). Seek to
					// the expected offset according to the server and try again.
					uploaded = ex.errorValue
							.getIncorrectOffsetValue()
							.getCorrectOffset();

				} else {
					// Some other error occurred, give up.
					System.err.println("Error uploading to Dropbox: " + ex.getMessage());
					//System.exit(1);
					return;
				}
			} catch (UploadSessionFinishErrorException ex) {
				if (ex.errorValue.isLookupFailed() && ex.errorValue.getLookupFailedValue().isIncorrectOffset()) {
					thrown = ex;
					// server offset into the stream doesn't match our offset (uploaded). Seek to
					// the expected offset according to the server and try again.
					uploaded = ex.errorValue
							.getLookupFailedValue()
							.getIncorrectOffsetValue()
							.getCorrectOffset();

				} else {
					// some other error occurred, give up.
					System.err.println("Error uploading to Dropbox: " + ex.getMessage());
					// System.exit(1);
					return;
				}
			} catch (DbxException ex) {
				System.err.println("Error uploading to Dropbox: " + ex.getMessage());
				//System.exit(1);
				return;
			} catch (IOException ex) {
				System.err.println("Error reading from file \"" + localFile + "\": " + ex.getMessage());
				//System.exit(1);
				return;
			}
		}

		// if we made it here, then we must have run out of attempts
		System.err.println("Maxed out upload attempts to Dropbox. Most recent error: " + thrown.getMessage());
	}
	*/
	public static void mountFolder(Path nameFolder) throws DbxException {

		List<SharedFolderMetadata> result = client.sharing().listMountableFoldersBuilder().start().getEntries();
		for (SharedFolderMetadata x : result) {
			if (x.getName().equals(nameFolder.toString())) {
				if (x.getPathLower() == null) {
					client.sharing().mountFolder(x.getSharedFolderId());
				}
			}
		}

	}
	public static String getSharedFolderId(Path path) throws DbxException {
		return ((FolderMetadata) client.files().getMetadata(path.toString())).getSharedFolderId();
	}

	public static int checkIfOwnerOfFile(Path path){ //1 true, 0 false, -1 non esiste file
		try {
			if(DropboxClient.getClient().sharing().getFolderMetadata(DropboxClient.getSharedFolderId(path)).getAccessType().compareTo(AccessLevel.OWNER)==0){
				return 1;
			}
			return 0;
		} catch (DbxException e) {
			return -1;
		}
	}
	public static void createFolder(Path path,Boolean shared) {
		try {
			client.files().getMetadata(path.toString());
		} catch (DbxException e){
			try {
				client.files().createFolderV2(path.toString(), false);
				if(shared){
					client.sharing().shareFolder(path.toString());

				}

			} catch (DbxException e1) {
				throw new Main.ExecutionException("createFolder",e);

			}
		}

	}
	public static void addUsersToFolder(AccessLevel accessLevel, Path path, List<User> users) throws DbxException {
		if (client.files().getMetadata(path.toString()).toString().contains("shared_folder_id")) {
			List<AddMember> newAddMembers = new ArrayList<>();
			users.forEach(user->{
				newAddMembers.add(new AddMember(MemberSelector.email(user.getEmail()),accessLevel));
			});
			//TODO check lambda, pretty sure però
			client.sharing().addFolderMember(getSharedFolderId(path), newAddMembers);
		}else{
			throw new Main.ExecutionException("addUsersToFolder");

		}

	}
	public static void removeUsersFromFolder(Path path, List<User> users) throws DbxException {
		if (client.files().getMetadata(path.toString()).toString().contains("shared_folder_id")) {
			users.forEach(user->{
				try {
					client.sharing().removeFolderMember(getSharedFolderId(path),MemberSelector.email(user.getEmail()),false);
				} catch (DbxException e) {
					throw new Main.ExecutionException("removeUsersFromFile",e);
				}
			});

		}else{
			throw new Main.ExecutionException("removeUsersFromFolder");

		}
	}
}