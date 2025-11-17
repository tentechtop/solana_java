package com.bit.solana.p2p.peer;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;


@Slf4j
@Data
@Component
@ConfigurationProperties(prefix = "system")
public class Settings {
    private byte[] localPeerId;
    private int bucketSize;
    private int findNodeSize;
    private int maximumLastSeenAgeToConsiderAlive;
    private int identifierSize;
    private int pingScheduleTimeValue;
    private TimeUnit pingScheduleTimeUnit = TimeUnit.SECONDS;
    private int dhtExecutorPoolSize;
    private int scheduledExecutorPoolSize;
    private boolean enabledFirstStoreRequestForcePass;



}

