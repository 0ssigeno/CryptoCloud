package Execution;

import Management.Caller;
import Management.PwdEntry;
import com.jcraft.jsch.*;

import javax.swing.*;
import java.io.InputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Vector;

class Connection {
	private List<PwdEntry> pwdEntryList;

	Connection(Caller caller) {
		this.pwdEntryList = caller.listPwdEntries();
	}

	private List<PwdEntry> removeElements(List<PwdEntry> pwdEntryList, String element) {
		List<String> objects = new ArrayList<>();
		switch (element) {
			case "username":
				pwdEntryList.forEach(pwdEntry -> {
					if (!objects.contains(pwdEntry.getUsername())) {
						objects.add(pwdEntry.getUsername());
					}
				});
				break;
			case "machine":
				pwdEntryList.forEach(pwdEntry -> {
					if (!objects.contains(pwdEntry.getMachine())) {
						objects.add(pwdEntry.getMachine());
					}
				});
				break;
			default:
				throw new Main.ExecutionException("removeElements");
		}
		String object;
		if (objects.size() > 1) {
			System.out.println("These are the " + element + "s:");
			objects.forEach(System.out::println);
			System.out.println("Please enter a " + element);

			object = Main.inputUser();
			while (!objects.contains(object)) {
				System.err.println("Please enter a valid " + element);
				object = Main.inputUser();
			}
		} else {
			object = objects.get(0);
			System.out.println("You can only connect to " + object);
		}
		String finalObject = object;
		List<PwdEntry> validPwdEntry = new ArrayList<>();
		switch (element) {
			case "username":
				pwdEntryList.forEach(pwdEntry -> {
					if (pwdEntry.getUsername().equals(finalObject)) {
						validPwdEntry.add(pwdEntry);
					}
				});
				break;
			case "machine":
				pwdEntryList.forEach(pwdEntry -> {
					if (pwdEntry.getMachine().equals(finalObject)) {
						validPwdEntry.add(pwdEntry);
					}
				});
				break;
			default:
				throw new Main.ExecutionException("removeElements");
		}

		return validPwdEntry;
	}

	void connect() {
		List<PwdEntry> validPwdEntries = pwdEntryList;
		validPwdEntries = removeElements(validPwdEntries, "machine");
		if (validPwdEntries.size() > 1) {
			validPwdEntries = removeElements(validPwdEntries, "username");
			if (validPwdEntries.size() > 1) {
				throw new Main.ExecutionException("connect");
			}
		}
		PwdEntry pwdEntry = validPwdEntries.get(0);
		switch (pwdEntry.getConnection()) {
			case SFTP:
				connectToSFTP(pwdEntry);
				break;
			case SSH:
				connectToSSH(pwdEntry);
				break;
			default:
				System.err.println("This type of connection is not supported");
				break;
		}


	}

	private void connectToSSH(PwdEntry pwdEntry) {
		try {
			JSch jsch = new JSch();
			Session session = jsch.getSession(pwdEntry.getUsername(), pwdEntry.getMachine(), Integer.valueOf(pwdEntry.getPort()));
			session.setPassword(pwdEntry.getPassword());
			session.setConfig("StrictHostKeyChecking", "no");
			session.connect(3000);

			Channel channel = session.openChannel("shell");

			channel.setInputStream(System.in);
			channel.setOutputStream(System.out);
			channel.connect(3000);
			//TODO VOGLIO UN LISTENER IN QUEL THREAD
			while (channel.isConnected()) {
				Thread.sleep(5000);
				//Channel -riga 299
			}
			//TODO poichè passo gli stream standard,
			// TODO quando il thread muore (perchè si, channel in realtà è un fottuto thread)
			// TODO gli stream vengono chiusi e non si possono riaprire -> fixare non so come


			System.exit(0);

		} catch (JSchException e) {
			throw new Main.ExecutionException("SSHConnection", e, this);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
	}

	private void connectToSFTP(PwdEntry pwdEntry) {
		try {
			JSch jsch = new JSch();

			Session session = jsch.getSession(pwdEntry.getUsername(), pwdEntry.getMachine(), Integer.valueOf(pwdEntry.getPort()));
			Properties config = new Properties();
			config.put("StrictHostKeyChecking", "no");
			session.setConfig(config);
			session.setPassword(pwdEntry.getPassword());

			session.connect(3000);
			Channel channel = session.openChannel("sftp");

			channel.setInputStream(System.in);
			channel.setOutputStream(System.out);

			channel.connect();
			ChannelSftp c = (ChannelSftp) channel;

			InputStream in = System.in;
			PrintStream out = System.out;

			List<String> cmds = new ArrayList<>();
			byte[] buf = new byte[1024];
			int i;
			String str;
			int level = 0;

			label:
			while (true) {
				out.print("sftp> ");
				cmds.clear();
				//old cmds.removeAllElements();
				i = in.read(buf, 0, 1024);
				if (i <= 0) break;

				i--;
				if (i > 0 && buf[i - 1] == 0x0d) {
					i--;
				}
				int s = 0;
				for (int ii = 0; ii < i; ii++) {
					if (buf[ii] == ' ') {
						if (ii - s > 0) {
							//cmds.addElement(new String(buf, s, ii-s));
							cmds.add(new String(buf, s, ii - s));
						}
						while (ii < i) {
							if (buf[ii] != ' ') break;
							ii++;
						}
						s = ii;
					}
				}
				if (s < i) {
					//cmds.addElement(new String(buf, s, i-s));
					cmds.add(new String(buf, s, i - s));
				}
				if (cmds.size() == 0) continue;

				//String cmd=(String)cmds.get(0);
				String cmd = cmds.get(0);
				switch (cmd) {
					case "quit":
						c.quit();
						break label;
					case "exit":
						c.exit();
						break label;
					case "rekey":
						session.rekey();
						continue;
					case "compression":
						if (cmds.size() < 2) {
							out.println("compression level: " + level);
							continue;
						}
						level = Integer.parseInt(cmds.get(1));
						if (level == 0) {
							session.setConfig("compression.s2c", "none");
							session.setConfig("compression.c2s", "none");
						} else {
							session.setConfig("compression.s2c", "zlib@openssh.com,zlib,none");
							session.setConfig("compression.c2s", "zlib@openssh.com,zlib,none");
						}
						session.rekey();
						continue;
					case "cd":
					case "lcd": {
						if (cmds.size() < 2) continue;
						String path = cmds.get(1);
						try {
							if (cmd.equals("cd")) c.cd(path);
							else c.lcd(path);
						} catch (SftpException e) {
							System.out.println(e.toString());
						}
						continue;
					}
					case "rm":
					case "rmdir":
					case "mkdir": {
						if (cmds.size() < 2) continue;
						String path = cmds.get(1);
						try {
							switch (cmd) {
								case "rm":
									c.rm(path);
									break;
								case "rmdir":
									c.rmdir(path);
									break;
								default:
									c.mkdir(path);
									break;
							}
						} catch (SftpException e) {
							System.out.println(e.toString());
						}
						continue;
					}
					case "chgrp":
					case "chown":
					case "chmod": {
						if (cmds.size() != 3) continue;
						String path = cmds.get(2);
						int foo = 0;
						if (cmd.equals("chmod")) {
							byte[] bar = (cmds.get(1)).getBytes();
							int k;
							for (byte aBar : bar) {
								k = aBar;
								if (k < '0' || k > '7') {
									foo = -1;
									break;
								}
								foo <<= 3;
								foo |= (k - '0');
							}
							if (foo == -1) continue;
						} else {
							try {
								foo = Integer.parseInt(cmds.get(1));
							} catch (Exception e) {
								continue;
							}
						}
						try {
							switch (cmd) {
								case "chgrp":
									c.chgrp(foo, path);
									break;
								case "chown":
									c.chown(foo, path);
									break;
								case "chmod":
									c.chmod(foo, path);
									break;
							}
						} catch (SftpException e) {
							System.out.println(e.toString());
						}
						continue;
					}
					case "pwd":
					case "lpwd":
						str = (cmd.equals("pwd") ? "Remote" : "Local");
						str += " working directory: ";
						if (cmd.equals("pwd")) str += c.pwd();
						else str += c.lpwd();
						out.println(str);
						continue;
					case "ls":
					case "dir": {
						String path = ".";
						if (cmds.size() == 2) path = cmds.get(1);
						try {
							Vector vv = c.ls(path);
							if (vv != null) {
								for (Object obj : vv) {

									if (obj instanceof ChannelSftp.LsEntry) {
										out.println(((ChannelSftp.LsEntry) obj).getLongname());
									}

								}
							}
						} catch (SftpException e) {
							System.out.println(e.toString());
						}
						continue;
					}
					case "lls":
					case "ldir": {
						String path = ".";
						if (cmds.size() == 2) path = cmds.get(1);
						try {
							java.io.File file = new java.io.File(path);
							if (!file.exists()) {
								out.println(path + ": No such file or directory");
								continue;
							}
							if (file.isDirectory()) {
								String[] list = file.list();
								if (list != null) {
									for (String aList : list) {
										out.println(aList);
									}
								}
								continue;
							}
							out.println(path);
						} catch (Exception e) {
							e.printStackTrace();
						}
						continue;
					}
					case "get":
					case "get-resume":
					case "get-append":
					case "put":
					case "put-resume":
					case "put-append": {
						if (cmds.size() != 2 && cmds.size() != 3) continue;
						String p1 = cmds.get(1);
						String p2 = ".";
						if (cmds.size() == 3) {
							p2 = cmds.get(2);
						}
						try {
							SftpProgressMonitor monitor = new MyProgressMonitor();
							if (cmd.startsWith("get")) {
								int mode = ChannelSftp.OVERWRITE;
								if (cmd.equals("get-resume")) {
									mode = ChannelSftp.RESUME;
								} else if (cmd.equals("get-append")) {
									mode = ChannelSftp.APPEND;
								}
								c.get(p1, p2, monitor, mode);
							} else {
								int mode = ChannelSftp.OVERWRITE;
								if (cmd.equals("put-resume")) {
									mode = ChannelSftp.RESUME;
								} else if (cmd.equals("put-append")) {
									mode = ChannelSftp.APPEND;
								}
								c.put(p1, p2, monitor, mode);
							}
						} catch (SftpException e) {
							e.printStackTrace();
						}
						continue;
					}
					case "ln":
					case "symlink":
					case "rename":
					case "hardlink": {
						if (cmds.size() != 3) continue;
						String p1 = cmds.get(1);
						String p2 = cmds.get(2);
						try {
							switch (cmd) {
								case "hardlink":
									c.hardlink(p1, p2);
									break;
								case "rename":
									c.rename(p1, p2);
									break;
								default:
									c.symlink(p1, p2);
									break;
							}
						} catch (SftpException e) {
							e.printStackTrace();
						}
						continue;
					}
					case "df": {
						if (cmds.size() > 2) continue;
						String p1 = cmds.size() == 1 ? "." : cmds.get(1);
						SftpStatVFS stat = c.statVFS(p1);

						long size = stat.getSize();
						long used = stat.getUsed();
						long avail = stat.getAvailForNonRoot();
						long root_avail = stat.getAvail();
						long capacity = stat.getCapacity();

						System.out.println("Size: " + size);
						System.out.println("Used: " + used);
						System.out.println("Avail: " + avail);
						System.out.println("(root): " + root_avail);
						System.out.println("%Capacity: " + capacity);

						continue;
					}
					case "stat":
					case "lstat": {
						if (cmds.size() != 2) continue;
						String p1 = cmds.get(1);
						SftpATTRS attrs = null;
						try {
							if (cmd.equals("stat")) attrs = c.stat(p1);
							else attrs = c.lstat(p1);
						} catch (SftpException e) {
							System.out.println(e.toString());
						}
						if (attrs != null) {
							out.println(attrs);
						}
						continue;
					}
					case "readlink": {
						if (cmds.size() != 2) continue;
						String p1 = cmds.get(1);
						try {
							String filename = c.readlink(p1);
							out.println(filename);
						} catch (SftpException e) {
							System.out.println(e.toString());
						}
						continue;
					}
					case "realpath": {
						if (cmds.size() != 2) continue;
						String p1 = cmds.get(1);
						try {
							String filename = c.realpath(p1);
							out.println(filename);
						} catch (SftpException e) {
							e.printStackTrace();
						}
						continue;
					}
					case "version":
						out.println("SFTP protocol version " + c.version());
						continue;
					case "help":
					case "?":
						String help = "      Available commands:\n" +
								"      * means unimplemented command.\n" +
								"cd path                       Change remote directory to 'path'\n" +
								"lcd path                      Change local directory to 'path'\n" +
								"chgrp grp path                Change group of file 'path' to 'grp'\n" +
								"chmod mode path               Change permissions of file 'path' to 'mode'\n" +
								"chown own path                Change owner of file 'path' to 'own'\n" +
								"df [path]                     Display statistics for current directory or\n" +
								"                              filesystem containing 'path'\n" +
								"help                          Display this help text\n" +
								"get remote-path [local-path]  Download file\n" +
								"get-resume remote-path [local-path]  Resume to download file.\n" +
								"get-append remote-path [local-path]  Append remote file to local file\n" +
								"hardlink oldpath newpath      Hardlink remote file\n" +
								"*lls [ls-options [path]]      Display local directory listing\n" +
								"ln oldpath newpath            Symlink remote file\n" +
								"*lmkdir path                  Create local directory\n" +
								"lpwd                          Print local working directory\n" +
								"ls [path]                     Display remote directory listing\n" +
								"*lumask umask                 Set local umask to 'umask'\n" +
								"mkdir path                    Create remote directory\n" +
								"put local-path [remote-path]  Upload file\n" +
								"put-resume local-path [remote-path]  Resume to upload file\n" +
								"put-append local-path [remote-path]  Append local file to remote file.\n" +
								"pwd                           Display remote working directory\n" +
								"stat path                     Display info about path\n" +
								"exit                          Quit sftp\n" +
								"quit                          Quit sftp\n" +
								"rename oldpath newpath        Rename remote file\n" +
								"rmdir path                    Remove remote directory\n" +
								"rm path                       Delete remote file\n" +
								"symlink oldpath newpath       Symlink remote file\n" +
								"readlink path                 Check the target of a symbolic link\n" +
								"realpath path                 Canonicalize the path\n" +
								"rekey                         Key re-exchanging\n" +
								"compression level             Packet compression will be enabled\n" +
								"version                       Show SFTP version\n" +
								"?                             Synonym for help";
						out.println(help);
						continue;
				}
				out.println("unimplemented command: " + cmd);
			}
			session.disconnect();
		} catch (Exception e) {
			e.printStackTrace();
		}
		System.exit(0);
	}

	private class MyProgressMonitor implements SftpProgressMonitor {
		ProgressMonitor monitor;
		long count = 0;
		long max = 0;
		private long percent = -1;

		public void init(int op, String src, String dest, long max) {
			this.max = max;
			monitor = new ProgressMonitor(null,
					((op == SftpProgressMonitor.PUT) ?
							"put" : "get") + ": " + src,
					"", 0, (int) max);
			count = 0;
			percent = -1;
			monitor.setProgress((int) this.count);
			monitor.setMillisToDecideToPopup(1000);
		}

		public boolean count(long count) {
			this.count += count;

			if (percent >= this.count * 100 / max) {
				return true;
			}
			percent = this.count * 100 / max;

			monitor.setNote("Completed " + this.count + "(" + percent + "%) out of " + max + ".");
			monitor.setProgress((int) this.count);

			return !(monitor.isCanceled());
		}

		public void end() {
			monitor.close();
		}
	}
}


