package de.qabel.desktop.daemon.management;

import de.qabel.core.config.Account;
import de.qabel.core.config.Identity;
import de.qabel.desktop.config.BoxSyncConfig;
import de.qabel.desktop.config.factory.BoxVolumeFactory;
import de.qabel.desktop.storage.BoxVolume;

public class BoxVolumeFactoryStub implements BoxVolumeFactory {
	public Account lastAccount;
	public Identity lastIdentity;
	public BoxVolume boxVolume = null;

	@Override
	public BoxVolume getVolume(Account account, Identity identity) {
		lastAccount = account;
		lastIdentity = identity;
		return boxVolume;
	}
}
