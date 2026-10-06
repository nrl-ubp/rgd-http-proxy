package com.ubp.rgd.proxy.services.wdx1;

import com.ubp.rgd.proxy.services.SecretsManagerForwardingTest;
import com.ubp.rgd.proxy.transform.EndPointTransformer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.ubp.rgd.proxy.services.TestSecretsManagers.CH;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Checks that {@link WDX1UtilsService} uses the resolved secrets manager for every transformer call.
 */
class WDX1UtilsServiceSecretsManagerTest {

    @Test
    @DisplayName("/utils/wdx1/concat reads and writes the tokens with the secrets manager")
    void wdx1ServiceForwards() throws Exception {
        EndPointTransformer transformer = SecretsManagerForwardingTest.echoTransformer();
        WDX1UtilsService service = new WDX1UtilsService();
        service.transformer = transformer;
        service.preFilterAuthEnabled = "false";
        service.wdx1ConcatConfigFile = "./config/wdx1_concat_config.json";
        service.init();

        WDX1ConcatRequest request = new WDX1ConcatRequest();
        request.setCountry("CH");
        request.setFirstName("RG{aaaaaaaaaa}");
        request.setLastName("RG{bbbbbbbbbb}");
        request.setBirthDate("RG{cccccccccc}");

        service.tokenConcat(request, CH);

        // once to unprotect the inputs, once to protect the concatenation
        verify(transformer, times(2)).transformData(any(), any(), any(), eq(CH));
    }

}
