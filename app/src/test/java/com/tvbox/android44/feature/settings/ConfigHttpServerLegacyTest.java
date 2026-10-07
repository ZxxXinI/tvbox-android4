package com.tvbox.android44.feature.settings;

import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Execute the existing HTTP/expiry/one-shot/port-release scenarios on API 16. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 16)
public class ConfigHttpServerLegacyTest extends ConfigHttpServerTest { }
