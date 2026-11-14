package com.kovil.infra.solapi.sms;

import com.kovil.infra.sms.SmsMessage;
import com.kovil.infra.sms.SmsSender;
import com.kovil.infra.sms.exception.SmsException;
import com.solapi.sdk.SolapiClient;
import com.solapi.sdk.message.model.Message;
import com.solapi.sdk.message.service.DefaultMessageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import static com.kovil.infra.sms.exception.SmsErrorCode.SMS_SEND_FAILED;

@Slf4j
@Component
@ConditionalOnProperty(name = "sms.provider", havingValue = "solapi")
public class SolapiSmsSender implements SmsSender {

    private final SolapiProperties properties;
    private final DefaultMessageService messageService;

    @Autowired
    public SolapiSmsSender(SolapiProperties properties) {
        this(properties, SolapiClient.INSTANCE.createInstance(properties.apiKey(), properties.apiSecret()));
    }

    // 테스트 주입용 seam(package-private) — 실제 SDK 클라이언트 생성 없이 목을 넣는다(선례 규약).
    SolapiSmsSender(SolapiProperties properties, DefaultMessageService messageService) {
        this.properties = properties;
        this.messageService = messageService;
    }

    @Override
    public void send(SmsMessage message) {
        Message solapiMessage = new Message();
        solapiMessage.setFrom(normalizePhone(properties.from()));
        solapiMessage.setTo(normalizePhone(message.toPhone()));
        solapiMessage.setText(message.text());

        try {
            messageService.send(solapiMessage);
        } catch (Exception e) {
            // 본문에 인증번호가 들어 있으므로 예외 메시지·본문을 로그에 싣지 않는다.
            log.error("[solapi] SMS 발송 실패 type={}", e.getClass().getSimpleName());
            throw new SmsException(SMS_SEND_FAILED);
        }
    }

    // Solapi 규격: 하이픈·+82를 제거한 01012345678 형태여야 한다(공식 SDK 예제로 확인).
    private String normalizePhone(String phone) {
        String digits = phone == null ? "" : phone.replaceAll("[^0-9]", "");
        if (digits.startsWith("82")) {
            digits = "0" + digits.substring(2);
        }
        return digits;
    }
}
