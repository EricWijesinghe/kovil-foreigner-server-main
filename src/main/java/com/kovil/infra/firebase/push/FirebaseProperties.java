package com.kovil.infra.firebase.push;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "firebase")
// 서비스 계정 JSON에서 client_email·private_key 값만 .env로 옮겨 심는다 — 파일 경로 방식은
// 배포 환경마다 경로가 달라 .env가 이식성을 잃는다(Apple .p8 본문 주입과 같은 선례).
public record FirebaseProperties(String projectId, String clientEmail, String privateKey) {
}
