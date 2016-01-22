package de.qabel.desktop.daemon.management;


import de.qabel.desktop.daemon.management.exception.TransferSkippedException;
import de.qabel.desktop.exceptions.QblStorageException;
import de.qabel.desktop.storage.BoxFile;
import de.qabel.desktop.storage.BoxNavigation;
import de.qabel.desktop.storage.BoxVolume;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.LinkedBlockingQueue;

import static de.qabel.desktop.daemon.management.Transaction.STATE.FINISHED;
import static de.qabel.desktop.daemon.management.Transaction.STATE.SKIPPED;

public class DefaultLoadManager implements LoadManager, Runnable {
	private final Logger logger = LoggerFactory.getLogger(DefaultLoadManager.class);
	private final LinkedBlockingQueue<Transaction> transactions = new LinkedBlockingQueue<>();

	@Override
	public List<Transaction> getTransactions() {
		return new LinkedList<>(transactions);
	}

	@Override
	public void addDownload(Download download) {
		logger.trace("download added: " + download.getSource() + " to " + download.getDestination());
		transactions.add(download);
	}

	@Override
	public void addUpload(Upload upload) {
		logger.trace("upload added: " + upload.getSource() + " to " + upload.getDestination());
		transactions.add(upload);
	}

	public void run() {
		try {
			while (!Thread.interrupted()) {
				next();
			}
		} catch (InterruptedException e) {
			logger.trace("loadManager stopped: " + e.getMessage());
		}
	}

	void next() throws InterruptedException {
		Transaction transaction = transactions.take();
		logger.trace("handling transaction  " + transaction);
		try {
			if (transaction instanceof Upload) {
				upload((Upload) transaction);
			} else {
				download((Download) transaction);
			}
		} catch (Exception e) {
			logger.error("Transaction failed: " + e.getMessage(), e);
		}
	}

	void download(Download download) throws Exception {
		if (!download.isValid()) {
			logger.trace("skipped invalid download " + download);
			return;
		}

		Path destination = download.getDestination();
		Path source = download.getSource();

		try {
			switch (download.getType()) {
				case UPDATE:
				case CREATE:
					if (download.isDir()) {
						Files.createDirectories(destination);
						break;
					}

					Path parent = source.getParent();
					BoxNavigation nav = navigate(parent, download.getBoxVolume());
					BoxFile file = nav.getFile(source.getFileName().toString());
					if (remoteChanged(download, file) || localIsNewer(destination, file)) {
						throw new TransferSkippedException();
					}

					try (InputStream stream = new BufferedInputStream(nav.download(file))) {
						Files.copy(stream, destination, StandardCopyOption.REPLACE_EXISTING);
					}
					break;
				case DELETE:
					Files.deleteIfExists(destination);
					break;
			}
			download.toState(FINISHED);
		} catch (TransferSkippedException e) {
			download.toState(SKIPPED);
		}
	}

	private boolean localIsNewer(Path destination, BoxFile file) {
		return file.mtime < destination.toFile().lastModified();
	}

	private boolean remoteChanged(Download download, BoxFile file) {
		return !Objects.equals(file.mtime, download.getMtime());
	}

	void upload(Upload upload) throws QblStorageException {
		try {
			if (!upload.isValid()) {
				throw new TransferSkippedException();
			}

			Path destination = upload.getDestination();
			Path source = upload.getSource();
			boolean isDir = Files.isDirectory(source);

			BoxVolume volume = upload.getBoxVolume();
			Path parent = destination;
			BoxNavigation dir;

			switch (upload.getType()) {
				case DELETE:
					parent = destination.getParent();
					dir = navigate(parent, volume);
					String fileName = destination.getFileName().toString();
					if (upload.isDir()) {
						dir.delete(dir.getFolder(fileName));
					} else {
						BoxFile file = dir.getFile(fileName);
						if (remoteIsNewer(upload, file)) {
							throw new TransferSkippedException();
						}
						dir.delete(file);
					}
					break;
				case UPDATE:
					parent = destination.getParent();
					dir = navigate(parent, volume);
					String filename = destination.getFileName().toString();
					BoxFile file = dir.getFile(filename);
					if (remoteIsNewer(upload, file)) {
						throw new TransferSkippedException();
					}
					dir.overwrite(filename, source.toFile());
					break;
				default:
					if (!isDir) {
						parent = destination.getParent();
					}
					dir = createDirectory(parent, volume);
					if (!isDir) {
						uploadFile(dir, source, destination);
					}
					break;
			}
			upload.toState(FINISHED);
		} catch (TransferSkippedException e) {
			upload.toState(SKIPPED);
			logger.trace("skipped invalid upload " + upload);
		}
	}

	private boolean remoteIsNewer(Upload upload, BoxFile file) {
		return file.mtime > upload.getMtime();
	}

	private BoxNavigation navigate(Path path, BoxVolume volume) throws QblStorageException {
		BoxNavigation nav = volume.navigate();
		for (int i = 0; i < path.getNameCount(); i++) {
			nav = nav.navigate(path.getName(i).toString());
		}
		return nav;
	}

	private void uploadFile(BoxNavigation dir, Path source, Path destination) throws QblStorageException {
		dir.upload(destination.getFileName().toString(), source.toFile());
	}

	private void overwriteFile(BoxNavigation dir, Path source, Path destination) throws QblStorageException {
		dir.overwrite(destination.getFileName().toString(), source.toFile());
	}

	private BoxNavigation createDirectory(Path destination, BoxVolume volume) throws QblStorageException {
		BoxNavigation nav = volume.navigate();
		for (int i = 0; i < destination.getNameCount(); i++) {
			String name = destination.getName(i).toString();
			try {
				nav = nav.navigate(name);
			} catch (IllegalArgumentException e) {
				nav = nav.navigate(nav.createFolder(name));
			}
		}
		return nav;
	}
}
