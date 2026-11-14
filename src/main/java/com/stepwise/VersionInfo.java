package com.stepwise;

import java.io.InputStream;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads the plugin version from the bundled stepwise-version.properties.
 */
public final class VersionInfo
{
	private static final Logger log = LoggerFactory.getLogger(VersionInfo.class);
	private static final String PROPS_FILE = "/com/stepwise/stepwise-version.properties";
	private static final String FALLBACK = "dev";

	private static final String VERSION;

	static
	{
		String v = FALLBACK;
		try (InputStream in = VersionInfo.class.getResourceAsStream(PROPS_FILE))
		{
			if (in != null)
			{
				Properties props = new Properties();
				props.load(in);
				String raw = props.getProperty("version");
				if (raw != null && !raw.isEmpty())
				{
					v = raw;
				}
			}
		}
		catch (Exception e)
		{
			log.warn("Failed to read {}", PROPS_FILE, e);
		}
		VERSION = v;
	}

	private VersionInfo()
	{
	}

	public static String getVersion()
	{
		return VERSION;
	}
}
