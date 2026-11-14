package com.kovil.infra.push;

public interface PushSender {

    PushSendResult send(PushMessage message);
}
