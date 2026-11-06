package app.rawline.mobile.aidl;

import app.rawline.mobile.aidl.ISagerNetServiceCallback;

interface ISagerNetService {
  int getState();
  String getProfileName();
  String openConnectAuth();
  String replyOpenConnectAuth(String session, String tag, String challengeId, String valuesJson, boolean cancel);

  void registerCallback(in ISagerNetServiceCallback cb, int id);
  oneway void unregisterCallback(in ISagerNetServiceCallback cb);
  oneway void resetTraffic(in long[] profileIds);

  // The running instance's URL test in ms, or -2 when it failed through an OpenVPN/OpenConnect exit whose tunnel is up.
  int urlTest();

  // Refreshes the running instance's remote rule-sets; blocks up to about 60 s. Returns {"updated":n,"error":"text"}.
  String updateRuleSets();

  // The running auto-selector (bg.autoselector.AutoSelectorStatus as JSON; the member table only withMembers, which
  // cbAutoSelectorUpdate never carries); re-probe every running member; pin a member profile (-1 = automatic). Both
  // actions return at once and report through cbAutoSelectorUpdate.
  String autoSelectorStatus(boolean withMembers);
  void autoSelectorRecheck();
  void autoSelectorSelect(long memberProfileId);
}
