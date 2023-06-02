package com.javaotel.lokiotelapp;

import java.util.logging.Logger;

import org.apache.logging.log4j.LogManager;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import io.micrometer.core.instrument.MeterRegistry;


@SpringBootApplication
@RestController
public class LokiOtelAppApplication {

    @Autowired
    private MeterRegistry meterRegistry;

	
	private static final org.apache.logging.log4j.Logger log4jLogger =
	LogManager.getLogger("log4j-logger");
	//private static final org.slf4j.Logger slf4jLogger = LoggerFactory.getLogger("slf4j-logger");
	//private static final java.util.logging.Logger julLogger = Logger.getLogger("jul-logger");


	public static void main(String[] args) {
		SpringApplication.run(LokiOtelAppApplication.class, args);
	}


	@GetMapping("/")
    public String root(@RequestParam(value = "name", defaultValue = "World") String name, @RequestHeader HttpHeaders headers) {
        log4jLogger.error(headers.toString());
        log4jLogger.error(String.format("Hello %s!!", name));
        log4jLogger.debug("Debugging log");
        log4jLogger.info("Info log");
        log4jLogger.warn("Hey, This is a warning!");
        log4jLogger.error("Oops! We have an Error. OK");
        meterRegistry.counter("requests_root_total").increment();
        return String.format("Hello %s!!", name);
    }

    @GetMapping("/io_task")
    public String io_task(@RequestParam(value = "name", defaultValue = "World") String name) throws InterruptedException {
        Thread.sleep(1000);
        log4jLogger.info("io_task");
        return "io_task";
    }

    @GetMapping("/cpu_task")
    public String cpu_task(@RequestParam(value = "name", defaultValue = "World") String name) {
        for (int i = 0; i < 100; i++) {
            int tmp = i * i * i;
        }
        log4jLogger.info("cpu_task");
        return "cpu_task";
    }

    @GetMapping("/random_sleep")
    public String random_sleep(@RequestParam(value = "name", defaultValue = "World") String name) throws InterruptedException {
        Thread.sleep((int) (Math.random() / 5 * 10000));
        log4jLogger.info("random_sleep");
        return "random_sleep";
    }

    @GetMapping("/chain")
    public String chain(@RequestParam(value = "name", defaultValue = "World") String name) throws InterruptedException, IOException {
        String TARGET_ONE_HOST = System.getenv().getOrDefault("TARGET_ONE_HOST", "localhost");
        String TARGET_TWO_HOST = System.getenv().getOrDefault("TARGET_TWO_HOST", "localhost");
        logger.debug("chain is starting");
        Request.Get("http://localhost:8080/")
                .execute().returnContent();
        Request.Get(String.format("http://%s:8080/io_task", TARGET_ONE_HOST))
                .execute().returnContent();
        Request.Get(String.format("http://%s:8080/cpu_task", TARGET_TWO_HOST))
                .execute().returnContent();
        logger.debug("chain is finished");
        return "chain";
    }

    
    @GetMapping("/error_test")
    public String error_test(@RequestParam(value = "name", defaultValue = "World") String name) throws Exception {
        throw new Exception("Error test");
    }


}
