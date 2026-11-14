package com.kovil.infra.sms;

public interface SmsSender {
    void send(SmsMessage message);
}
