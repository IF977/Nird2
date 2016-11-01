package org.briarproject.android.controller;

import android.content.SharedPreferences;

import org.briarproject.android.controller.handler.ResultHandler;
import org.briarproject.api.crypto.CryptoComponent;
import org.briarproject.api.crypto.CryptoExecutor;
import org.briarproject.api.crypto.PasswordStrengthEstimator;
import org.briarproject.api.crypto.SecretKey;
import org.briarproject.api.db.DatabaseConfig;

import java.util.concurrent.Executor;
import java.util.logging.Logger;

import javax.inject.Inject;

public class SetupControllerImpl extends PasswordControllerImpl
		implements SetupController {

	private static final Logger LOG =
			Logger.getLogger(SetupControllerImpl.class.getName());

	private final PasswordStrengthEstimator strengthEstimator;

	@Inject
	SetupControllerImpl(SharedPreferences briarPrefs,
			DatabaseConfig databaseConfig,
			@CryptoExecutor Executor cryptoExecutor, CryptoComponent crypto,
			PasswordStrengthEstimator strengthEstimator) {
		super(briarPrefs, databaseConfig, cryptoExecutor, crypto);
		this.strengthEstimator = strengthEstimator;
	}

	@Override
	public float estimatePasswordStrength(String password) {
		return strengthEstimator.estimateStrength(password);
	}

	@Override
	public void storeAuthorInfo(final String password, final String nickname,
			final ResultHandler<Void> resultHandler) {
		cryptoExecutor.execute(new Runnable() {
			@Override
			public void run() {
				SecretKey key = crypto.generateSecretKey();
				databaseConfig.setEncryptionKey(key);
				String hex = encryptDatabaseKey(key, password);
				storeEncryptedDatabaseKey(hex);
				databaseConfig.setAuthorNick(nickname);
				resultHandler.onResult(null);
			}
		});
	}

}
