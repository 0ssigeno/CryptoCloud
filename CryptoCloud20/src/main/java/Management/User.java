package Management;

import Execution.Main;
import Management.Cloud.Dropbox;
import com.dropbox.core.DbxException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.*;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAKeyGenParameterSpec;
import java.security.spec.X509EncodedKeySpec;

public class User {
	final Path OWN_MESSAGE_PASSING;
	private final String email;
	private final Boolean verified;
	private PublicKey publicKey;
	private PrivateKey privateKey;

	User(UserBuilder builder) {

		this.email = builder.email;
		this.publicKey = builder.publicKey;
		this.verified = builder.verified;
		this.privateKey = builder.privateKey;
		OWN_MESSAGE_PASSING = Dropbox.MESSAGE_PASSING.resolve(email);

	}

	static Path exportKeysInLocal(KeyPair keyPair, Path publicKeyPath, Path privateKeyPath) {
		try {
			byte[] privateKey = keyPair.getPrivate().getEncoded();
			byte[] publicKey = keyPair.getPublic().getEncoded();
			Files.write(privateKeyPath, privateKey);
			return Files.write(publicKeyPath, publicKey);

		} catch (IOException e) {
			throw new Main.ExecutionException("exportKeysInLocal", e);
		}
	}

	static KeyPair generateKeyPair() {
		try {
			//KeyPairGenerator keyPair = KeyPairGenerator.getInstance("RSA", "BC");
			KeyPairGenerator keyPair = KeyPairGenerator.getInstance("RSA");
			keyPair.initialize(new RSAKeyGenParameterSpec(3072, RSAKeyGenParameterSpec.F4));
			return keyPair.generateKeyPair();
		} catch (NoSuchAlgorithmException /*| NoSuchProviderException*/ |
				InvalidAlgorithmParameterException e) {
			throw new Main.ExecutionException("generateKeyPair", e);
		}

	}

	static PublicKey importPublic(Path path) throws IOException {
		try {
			//KeyFactory keyFact = KeyFactory.getInstance("RSA", "BC");
			KeyFactory keyFact = KeyFactory.getInstance("RSA");
			return keyFact.generatePublic(new X509EncodedKeySpec(Files.readAllBytes(path)));
		} catch (NoSuchAlgorithmException /*| NoSuchProviderException */ |
				InvalidKeySpecException e) {
			throw new Main.ExecutionException("importPublic", e);
		}


	}

	private static PrivateKey importPrivate(Path path) {

		try {
			//KeyFactory keyFact = KeyFactory.getInstance("RSA", "BC");
			KeyFactory keyFact = KeyFactory.getInstance("RSA");
			return keyFact.generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(path)));
		} catch (NoSuchAlgorithmException /*| NoSuchProviderException*/ |
				InvalidKeySpecException | IOException e) {
			throw new Main.ExecutionException("importPrivate", e);
		}

	}

	@Override
	public boolean equals(Object other) {
		if (other == null) return false;
		if (other == this) return true;
		if (!(other instanceof User)) return false;
		User otherMyClass = (User) other;
		return (this.getEmail().equals(otherMyClass.getEmail()));

	}

	@Override
	public String toString() {
		return this.getEmail();
	}

	PrivateKey getPrivateKey() {
		if (privateKey == null) {
			throw new IllegalStateException("PrivateKey not initialized.");
		}
		return privateKey;
	}


	void setPrivateKey(PrivateKey privateKey) {
		this.privateKey = privateKey;
	}

	public PublicKey getPublicKey() {
		if (publicKey == null) {
			throw new IllegalStateException("PublicKey not initialized.");
		}
		return publicKey;
	}

	void removePublicKey() {
		try {
			Dropbox.getClient().files().deleteV2(Dropbox.PUBLIC_KEYS.resolve(this.email + Main.END_PUBLIC).toString());
		} catch (DbxException e) {
			throw new Main.ExecutionException("removePublicKey", e, this);
		}
	}

	void setPublicKey(PublicKey publicKey) {
		this.publicKey = publicKey;
	}

	public String getEmail() {
		if (email == null) {
			throw new IllegalStateException("Email not initialized.");
		}
		return email;
	}

	public Boolean getVerified() {
		if (verified == null) {
			throw new IllegalStateException("Verified not initialized.");
		}
		return verified;
	}

	void localDelete() {
		Path publicFile = Main.MY_TEMP_PATH.resolve(email + Main.END_PUBLIC);
		Path signatureFile = Main.MY_TEMP_PATH.resolve(email + Main.END_SIGNED);
		Path adminFile = Main.MY_TEMP_PATH.resolve("admin" + Main.END_ADMIN);
		Main.deleteLocalFiles(publicFile, signatureFile, adminFile);

	}


	public static class UserBuilder {
		private final String email;
		private PublicKey publicKey;
		private Boolean verified;
		private PrivateKey privateKey;

		public UserBuilder(String email) {
			this.email = email;
		}

		@Override
		public boolean equals(Object other) {
			if (other == null) return false;
			if (other == this) return true;
			if (!(other instanceof UserBuilder)) return false;
			UserBuilder otherMyClass = (UserBuilder) other;
			return (this.email.equals(otherMyClass.email));

		}

		public UserBuilder setPublicKey() {
			try {
				this.publicKey = importPublic(Dropbox.download(Dropbox.PUBLIC_KEYS, email, Main.END_PUBLIC));
			} catch (IOException | DbxException e) {
				throw new Main.ExecutionException("setPublicKey", e, this);
			}
			return this;
		}

		public UserBuilder setVerified() {
			if (publicKey != null) {
				try {
					Path adminPublic = Dropbox.download(Dropbox.SIGNED_PUBLIC_KEYS,
							"admin", Main.END_ADMIN);
					Path signatureFile = Dropbox.download(Dropbox.SIGNED_PUBLIC_KEYS, email, Main.END_SIGNED);
					byte[] signature = Files.readAllBytes(signatureFile);
					PublicKey adminKey = importPublic(adminPublic);
					this.verified = Main.verifyPkcs1Signature(adminKey, this.publicKey.getEncoded(), signature);
				} catch (IOException | DbxException e) {
					e.printStackTrace();
					this.verified = false;
				}


			} else {
				this.verified = false;
			}
			return this;
		}

		public UserBuilder setCaller() {
			Path publicKeyPath = Main.MY_PERSONAL_PATH.resolve(email + Main.END_PUBLIC);
			Path privateKeyPath = Main.MY_PERSONAL_PATH.resolve(email + Main.END_PRIVATE);

			try {
				publicKey = importPublic(publicKeyPath);
			} catch (IOException e) {
				System.out.println("Can't find public key locally, trying to download it");
				try {
					publicKey = importPublic(Dropbox.download(Dropbox.PUBLIC_KEYS, email, Main.END_PUBLIC));
				} catch (IOException | DbxException e1) {
					System.out.println("Can't find even online, creating them");
				}
			}

			if (publicKey != null && Files.exists(privateKeyPath)) {
				privateKey = importPrivate(privateKeyPath);
				setVerified();

			} else {
				KeyPair keypair = generateKeyPair();
				exportKeysInLocal(keypair, publicKeyPath, privateKeyPath);
				publicKey = keypair.getPublic();
				privateKey = keypair.getPrivate();
				verified = false;
			}
			return this;
		}


		public User build() {
			return new User(this);
		}


	}

}
