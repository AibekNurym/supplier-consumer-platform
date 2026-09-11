package com.supplierconsumer.config;

import com.supplierconsumer.wire.PgJson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private static final Logger log = LoggerFactory.getLogger(WebConfig.class);

    private final AppProperties props;

    public WebConfig(AppProperties props) {
        this.props = props;
    }

    @Bean
    public PgJson pgJson() {
        return new PgJson(props.wire().zone());
    }

    /**
     * The Node app calls {@code app.use(cors())} with no arguments, which is a wide-open policy:
     * any origin, no credentials. Reproduced rather than tightened, because narrowing it would
     * break whichever consumer client lives outside this repository.
     */
    @Bean
    public CorsFilter corsFilter() {
        CorsConfiguration config = new CorsConfiguration();
        config.addAllowedOriginPattern("*");
        config.addAllowedHeader("*");
        config.addAllowedMethod("*");
        config.setAllowCredentials(false);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return new CorsFilter(source);
    }

    /**
     * Serves uploaded chat attachments, which are stored on disk and referenced from
     * {@code consumer_company_messages.attachment_url} as a root-relative {@code /uploads/...}
     * path. Like the Node version these are unauthenticated -- anyone holding the URL can fetch
     * the file.
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        Path uploads = Path.of(props.uploads().dir()).toAbsolutePath().normalize();
        registry.addResourceHandler("/uploads/**")
                .addResourceLocations(uploads.toUri().toString());
        log.info("Serving /uploads/** from {}", uploads);
    }

    /**
     * Widens the JSON converter to accept any content type.
     *
     * <p>{@code express.json()} simply skips a request whose Content-Type it does not recognise,
     * leaving {@code req.body} as an empty object, so the handler still ran and returned its own
     * validation error -- for instance {@code 400 "Email and password are required"}. Spring
     * would reject the same request with a 415 before the handler is reached, turning a
     * documented 400 into an undocumented 415. Controllers therefore bind bodies as optional and
     * treat a missing body as empty.
     */
    @Override
    public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
        converters.stream()
                .filter(MappingJackson2HttpMessageConverter.class::isInstance)
                .map(MappingJackson2HttpMessageConverter.class::cast)
                .forEach(converter -> converter.setSupportedMediaTypes(List.of(
                        MediaType.APPLICATION_JSON,
                        MediaType.valueOf("application/*+json"),
                        MediaType.TEXT_PLAIN,
                        MediaType.ALL)));
    }

    /**
     * multer's disk storage does not create its destination, so on a fresh checkout the very
     * first chat upload fails at write time. Creating it at startup removes that trap.
     */
    @Bean
    public UploadDirectories uploadDirectories() throws IOException {
        Path chat = Path.of(props.uploads().dir(), "chat").toAbsolutePath().normalize();
        Files.createDirectories(chat);
        log.info("Chat upload directory ready at {}", chat);
        return new UploadDirectories(chat);
    }

    public record UploadDirectories(Path chat) {
    }
}
