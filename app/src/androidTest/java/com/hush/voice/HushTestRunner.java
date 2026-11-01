package com.hush.voice;

import android.app.*;
import android.os.Bundle;
import java.lang.reflect.*;

/** Tiny Android-runtime test runner, avoiding an unnecessary test framework dependency. */
public final class HushTestRunner extends Instrumentation {
  private String selectedClass;

  @Override
  public void onCreate(Bundle arguments) {
    super.onCreate(arguments);
    selectedClass = arguments == null ? null : arguments.getString("class");
    start();
  }

  @Override
  public void onStart() {
    int passed = 0, failed = 0;
    StringBuilder report = new StringBuilder();
    for (Class<?> type :
        new Class<?>[] {HushTests.class, CloudTests.class, EvaluationUiTests.class})
      for (Method method : type.getDeclaredMethods()) {
        if (!method.getName().startsWith("test")) continue;
        if (selectedClass != null
            && !selectedClass.equals(type.getName())
            && !selectedClass.equals(type.getName() + "#" + method.getName())) continue;
        Object test;
        try {
          java.lang.reflect.Constructor<?> ctor =
              type.getDeclaredConstructor(Instrumentation.class);
          ctor.setAccessible(true);
          test = ctor.newInstance(this);
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
        try {
          Method setup = type.getDeclaredMethod("setUp");
          setup.setAccessible(true);
          setup.invoke(test);
          method.invoke(test);
          passed++;
          report.append("PASS ").append(method.getName()).append('\n');
        } catch (Throwable error) {
          failed++;
          Throwable cause = error instanceof InvocationTargetException ? error.getCause() : error;
          report.append("FAIL ").append(method.getName()).append(": ").append(cause).append('\n');
          android.util.Log.e("HushTests", method.getName(), cause);
        } finally {
          try {
            Method teardown = type.getDeclaredMethod("tearDown");
            teardown.setAccessible(true);
            teardown.invoke(test);
          } catch (Exception e) {
            failed++;
            report.append("FAIL teardown: ").append(e).append("\n");
          }
        }
      }
    if (passed == 0 && failed == 0) {
      failed++;
      report.append("FAIL no matching tests\n");
    }
    Bundle results = new Bundle();
    results.putString("stream", "\n" + report + "\n" + passed + " passed, " + failed + " failed\n");
    results.putInt("passed", passed);
    results.putInt("failed", failed);
    finish(failed == 0 ? Activity.RESULT_OK : Activity.RESULT_CANCELED, results);
  }
}
