package com.ooohyg.agent;

import com.ooohyg.agent.workflow.WorkflowCli;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * ooohyg-agent 启动类。
 *
 * <p>主模块此前缺少 {@code @SpringBootApplication} 启动入口，导致
 * 无法以 Spring Boot 应用方式运行（spring-boot-maven-plugin 的
 * repackage 找不到 main class）。本类补上该入口。
 */
@SpringBootApplication
public class OoohygAgentApplication {

    public static void main(String[] args) {
        if (args.length > 0 && "workflow".equals(args[0])) {
            int status = WorkflowCli.run(java.util.Arrays.copyOfRange(args, 1, args.length));
            if (status != 0) System.exit(status);
            return;
        }
        SpringApplication.run(OoohygAgentApplication.class, args);
    }
}
