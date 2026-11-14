package com.kovil.infra.kakao.local;

import com.kovil.infra.nearby.NearbyVendorException;
import feign.Response;
import feign.codec.ErrorDecoder;

public class KakaoLocalErrorDecoder implements ErrorDecoder {

    @Override
    public Exception decode(String methodKey, Response response) {
        return new NearbyVendorException("kakao local " + response.status());
    }
}