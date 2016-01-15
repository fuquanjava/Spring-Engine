package org.springframework.amqp.helloworld;

import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.util.StringUtils;

/**
 * fuquanemail@gmail.com 2016/1/8 9:03
 * description:
 * 1.0.0
 */
public class Test {
    public static void main(String[] args) {
        new AnnotationConfigApplicationContext(TestScheduled.class);


        System.err.println(getDiscountValue("50:50")); //�����, ����
//        2��1�����������1Ԫ�����
        System.err.println(getExchangeValue("1:2")); //���1,�����2


    }

    public static Double getDiscountValue(String value){
        if(StringUtils.isEmpty(value)){
            return null;
        }
        String [] arys = value.split(":");
        if(arys != null && arys.length == 2){
            double rmb = Double.parseDouble(arys[0]);
            double points = Double.parseDouble(arys[1]);
            return rmb / points;
        }
        return null;
    }


    public static Double getExchangeValue(String value){
        if(StringUtils.isEmpty(value)){
            return null;
        }
        String [] arys = value.split(":");
        if(arys != null && arys.length == 2){
            double points = Double.parseDouble(arys[0]);
            double rmb = Double.parseDouble(arys[1]);
            return rmb / points;
        }
        return null;
    }

}
