package app.rawline.mobile.aidl;

import app.rawline.mobile.aidl.SpeedDisplayData;
import app.rawline.mobile.aidl.TrafficDataBatch;

oneway interface ISagerNetServiceCallback {
  void stateChanged(int state, String profileName, String msg);
  void cbSpeedUpdate(in SpeedDisplayData stats);
  void cbTrafficUpdate(in TrafficDataBatch stats);
  void cbSelectorUpdate(long id);
  void cbAutoSelectorUpdate(String json);
}
