package com.yyx.ciyuantown.business.checkin;
import java.time.*;
import org.springframework.context.annotation.*;
@Configuration
public class CheckinClock {
    @Bean public Clock communityClock(){return Clock.system(ZoneId.of("Asia/Shanghai"));}
}
