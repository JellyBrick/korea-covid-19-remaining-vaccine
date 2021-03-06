package cmonster.utils;

import java.net.InetAddress;
import java.net.UnknownHostException;

public class OS {

	public static String getOsArchitecture() {
		return System.getProperty("os.arch");
	}

	public static String getOperatingSystem() {
		return System.getProperty("os.name");
	}
	
	public static String getOperatingSystemVersion() {
		return System.getProperty("os.version");
	}
	
	public static String getIP() throws UnknownHostException {
		InetAddress ip = InetAddress.getLocalHost();
		return ip.getHostAddress();
	}
	
	public static String getHostname() throws UnknownHostException {
		return InetAddress.getLocalHost().getHostName(); 
	}

    public static boolean isWindows() {
        return (getOperatingSystem().toLowerCase().contains("win"));
    }

    public static boolean isMac() {
        return (getOperatingSystem().toLowerCase().contains("mac"));
    }

    public static boolean isLinux() {
        return (getOperatingSystem().toLowerCase().contains("nix") || getOperatingSystem().toLowerCase().contains("nux") || getOperatingSystem().toLowerCase().indexOf("aix") > 0 );
    }

    public static boolean isSolaris() {
        return (getOperatingSystem().toLowerCase().contains("sunos"));
    }
	
}
