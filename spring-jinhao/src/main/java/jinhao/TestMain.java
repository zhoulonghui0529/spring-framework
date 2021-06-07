package jinhao;

import jinhao.config.AppConfig;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

public class TestMain{
	public static void main(String[] args){
		AnnotationConfigApplicationContext ac=new AnnotationConfigApplicationContext();
		ac.register(AppConfig.class);
		ac.refresh();
		ac.getBean("person");
	}
}
