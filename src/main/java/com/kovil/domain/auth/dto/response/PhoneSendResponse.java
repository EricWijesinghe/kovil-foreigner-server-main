package com.kovil.domain.auth.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "인증번호 발송 결과. 인증번호 자체는 어떤 경우에도 응답에 담기지 않는다.")
public record PhoneSendResponse(

        @Schema(description = "인증번호 만료까지 남은 초. 입력 화면의 카운트다운에 쓴다.", example = "300")
        long expiresInSeconds,

        // 이 값이 없으면 FE는 재발송 버튼을 언제 열어줄지 몰라 사용자가 눌렀다가 429를 본다.
        @Schema(description = "재발송이 가능해질 때까지 남은 초. 이 시간 동안 재발송 버튼을 잠그면 된다.",
                example = "60")
        long resendAvailableInSeconds
) {
}
