/*
 * Copyright (C) 2011 The original author or authors.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package com.zapta.apps.maniana.debug;

public enum DebugCommand {
    // NOTE: debug mode strings are not translated. English only.
    NOTIFICATION_SINGLE("Notification (1)"),
    NOTIFICATION_MULTI("Notification (17)"),
    NOTIFICATION_CLEAR("Notification (clear)"),
    NEW_USER("New user message"),
    ICS_MENU("ICS menu"),
    SHAKE_ON1("Shake detector 1"),
    SHAKE_OFF("Shake detector OFF"),
    EXIT("Exit debug mode");
    
    public final String text;
    
    private DebugCommand(String text) {
        this.text = text;
    }
}