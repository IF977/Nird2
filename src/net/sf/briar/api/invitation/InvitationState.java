package net.sf.briar.api.invitation;

public class InvitationState {

	private final int localInvitationCode, remoteInvitationCode;
	private final int localConfirmationCode, remoteConfirmationCode;
	private final boolean connectionFailed;
	private final boolean localCompared, remoteCompared;
	private final boolean localMatched, remoteMatched;

	public InvitationState(int localInvitationCode, int remoteInvitationCode,
			int localConfirmationCode, int remoteConfirmationCode,
			boolean connectionFailed, boolean localCompared,
			boolean remoteCompared, boolean localMatched,
			boolean remoteMatched) {
		this.localInvitationCode = localInvitationCode;
		this.remoteInvitationCode = remoteInvitationCode;
		this.localConfirmationCode = localConfirmationCode;
		this.remoteConfirmationCode = remoteConfirmationCode;
		this.connectionFailed = connectionFailed;
		this.localCompared = localCompared;
		this.remoteCompared = remoteCompared;
		this.localMatched = localMatched;
		this.remoteMatched = remoteMatched;
	}

	public int getLocalInvitationCode() {
		return localInvitationCode;
	}

	public int getRemoteInvitationCode() {
		return remoteInvitationCode;
	}

	public int getLocalConfirmationCode() {
		return localConfirmationCode;
	}

	public int getRemoteConfirmationCode() {
		return remoteConfirmationCode;
	}

	public boolean getConnectionFailed() {
		return connectionFailed;
	}

	public boolean getLocalCompared() {
		return localCompared;
	}

	public boolean getRemoteCompared() {
		return remoteCompared;
	}

	public boolean getLocalMatched() {
		return localMatched;
	}

	public boolean getRemoteMatched() {
		return remoteMatched;
	}
}