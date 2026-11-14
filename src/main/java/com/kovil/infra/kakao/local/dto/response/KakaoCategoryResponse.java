package com.kovil.infra.kakao.local.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record KakaoCategoryResponse(List<Document> documents, Meta meta) {

    public record Document(
            @JsonProperty("place_name") String placeName,
            @JsonProperty("x") String longitude,
            @JsonProperty("y") String latitude
    ){}
    ;

    public record Meta(
            @JsonProperty("total_count") Long totalCount
    ){}
}
