package com.supplierconsumer;

import com.supplierconsumer.config.AppProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import java.util.TimeZone;

@SpringBootApplication
@EnableConfigurationProperties(AppProperties.class)
public class SupplierConsumerApplication {

    private static final Logger log = LoggerFactory.getLogger(SupplierConsumerApplication.class);

    private final AppProperties props;

    public SupplierConsumerApplication(AppProperties props) {
        this.props = props;
    }

    public static void main(String[] args) {
        SpringApplication.run(SupplierConsumerApplication.class, args);
    }

    /**
     * Every timestamp column in this schema is `timestamp without time zone`, and the Node
     * driver turned those into wire values by reading the wall-clock number in the server
     * process's local zone. That makes the zone part of the API contract rather than a
     * deployment detail, so it is set explicitly and logged -- a host with a different
     * default would silently shift every timestamp the API returns.
     */
    @PostConstruct
    void pinTimeZone() {
        TimeZone.setDefault(TimeZone.getTimeZone(props.wire().timezone()));
        log.info("Wire timezone pinned to {} (timestamps are rendered by reading "
                + "`timestamp without time zone` values in this zone)", props.wire().timezone());
    }
}
