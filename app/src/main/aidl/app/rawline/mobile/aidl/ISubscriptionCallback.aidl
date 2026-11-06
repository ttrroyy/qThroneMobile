package app.rawline.mobile.aidl;

// state: 0 idle, 1 queued, 2 running. popup: the desktop would show the change window (manual refresh + sub_show_change_popup).
oneway interface ISubscriptionCallback {
  void onState(long gid, int state);
  void onReport(long gid, String title, String text, boolean popup);
  void onError(long gid, String message);
}
