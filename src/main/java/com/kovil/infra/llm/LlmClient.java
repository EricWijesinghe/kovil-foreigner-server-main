package com.kovil.infra.llm;

public interface LlmClient {

    String complete(String prompt);
}
