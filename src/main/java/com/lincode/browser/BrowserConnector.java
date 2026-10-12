package com.lincode.browser;

public interface BrowserConnector {
    String status();

    String connectDefault();

    String disconnect();
}
