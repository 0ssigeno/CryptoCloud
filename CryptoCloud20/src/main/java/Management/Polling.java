package Management;

import Management.Cloud.Dropbox;
import com.dropbox.core.DbxApiException;
import com.dropbox.core.DbxException;
import com.dropbox.core.NetworkIOException;
import com.dropbox.core.v2.files.*;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

public class Polling extends Thread {

	private Path path;
	private Caller caller;
	private volatile boolean shutdown;

	private Polling(Polling polling) {
		this.path = polling.getPath();
		this.caller = polling.getCaller();
		this.shutdown = false;
	}

	Polling(Path path, Caller caller) {
		this.path = path;
		this.caller = caller;
		this.shutdown = false;
	}

	private Caller getCaller() {
		if (caller == null) {
			throw new IllegalStateException("Caller not initialized.");
		}
		return caller;
	}

	public Path getPath() {
		if (path == null) {
			throw new IllegalStateException("Path not initialized.");
		}
		return path;
	}

	public void shutdown() {
		shutdown = true;
	}
	public void run() {

		long longpollTimeoutSecs = TimeUnit.MINUTES.toSeconds(5);


		try {
			String cursor = getLatestCursor(path.toString());


			while (!shutdown) {
				ListFolderLongpollResult result = Dropbox.getLongpollClient().files()
						.listFolderLongpoll(cursor, longpollTimeoutSecs);

				if (result.getChanges()) {
					try {
						cursor = answerChanges(cursor);
					} catch (DbxException e) {
						System.err.println("Can't read cursor " + e.getMessage());
					}
				}
				Long backOff = result.getBackoff();
				if (backOff != null) {
					try {
						System.out.printf("backing off for %d secs...\n", backOff);
						Thread.sleep(TimeUnit.SECONDS.toMillis(backOff));
					} catch (InterruptedException ex) {
						System.exit(0);
					}
				}
			}
		} catch (DbxApiException ex) {
			String message = ex.getUserMessage() != null ? ex.getUserMessage().getText() : ex.getMessage();
			System.err.println("Error making API call: " + message);
			System.exit(1);
		} catch (NetworkIOException ex) {
			System.err.println("You were AFK, restarting thread");
			Polling polling = new Polling(this);
			polling.start();
		} catch (DbxException ex) {
			System.err.println("Error making API call: " + ex.getMessage());
			System.exit(1);
		}
	}

	private String answerChanges(String cursor) throws DbxException {

		while (true) {
			ListFolderResult result = Dropbox.getClient().files()
					.listFolderContinue(cursor);

			for (Metadata metadata : result.getEntries()) {
				if (metadata instanceof FileMetadata) {
					System.out.print("You received a notification");
					caller.manageNotification(Paths.get(metadata.getPathLower()));
					Dropbox.getClient().files().deleteV2(metadata.getPathLower());
				}

			}
			cursor = result.getCursor();

			if (!result.getHasMore()) {
				break;
			}
		}

		return cursor;

	}

	private String getLatestCursor(String path)
			throws DbxException {
		ListFolderGetLatestCursorResult result = Dropbox.getClient().files()
				.listFolderGetLatestCursorBuilder(path)
				.withIncludeDeleted(true)
				.withIncludeMediaInfo(false)
				.withRecursive(true)
				.start();
		return result.getCursor();
	}
}
