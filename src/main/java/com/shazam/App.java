package com.shazam;

import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.DispatcherServlet;

public class App {
    public static void main(String[] args) throws Exception {
        AnnotationConfigWebApplicationContext context = new AnnotationConfigWebApplicationContext();
        context.register(AppConfig.class);

        DispatcherServlet dispatcherServlet = new DispatcherServlet(context);

        Tomcat tomcat = new Tomcat();
        tomcat.setPort(8080);
        tomcat.getConnector();

        Context ctx = tomcat.addContext("", null);
        Tomcat.addServlet(ctx, "dispatcherServlet", dispatcherServlet).setLoadOnStartup(1);
        ctx.addServletMappingDecoded("/*", "dispatcherServlet");

        tomcat.start();
        System.out.println("Server started on http://localhost:8080");
        tomcat.getServer().await();
    }
}
