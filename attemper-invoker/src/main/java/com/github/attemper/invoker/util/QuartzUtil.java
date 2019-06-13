package com.github.attemper.invoker.util;

import com.github.attemper.common.constant.CommonConstants;
import com.github.attemper.common.enums.CalendarType;
import com.github.attemper.common.exception.RTException;
import com.github.attemper.common.param.dispatch.calendar.CalendarGetParam;
import com.github.attemper.common.param.dispatch.calendar.DayCalendarListParam;
import com.github.attemper.common.param.dispatch.trigger.sub.*;
import com.github.attemper.common.result.dispatch.calendar.CalendarInfo;
import com.github.attemper.common.result.dispatch.calendar.DayCalendarConfig;
import com.github.attemper.common.util.DateTimeUtil;
import com.github.attemper.config.base.bean.SpringContextAware;
import com.github.attemper.core.service.calendar.CalendarService;
import com.github.attemper.invoker.job.ExecutableJob;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang.StringUtils;
import org.quartz.*;
import org.quartz.impl.calendar.BaseCalendar;
import org.quartz.impl.calendar.HolidayCalendar;
import org.quartz.impl.jdbcjobstore.Constants;
import org.quartz.impl.triggers.AbstractTrigger;

import java.util.*;

/**
 * operate quartz Job/JobKey/JobDetail/Trigger/TriggerKey...
 */
@Slf4j
public class QuartzUtil {

    public static JobDetail newJobDetail(String jobName, String tenantId) {
        return JobBuilder.newJob(ExecutableJob.class).withIdentity(jobName, tenantId).build();
    }

    public static <K extends CommonTriggerParam> TriggerBuilder buildTrigger(String tenantId, K item) {
        TriggerBuilder<Trigger> triggerBuilder = TriggerBuilder.newTrigger()
                .withIdentity(new TriggerKey(item.getTriggerName(), tenantId))
                .startAt(preHandleDate(item.getStartTime()))
                .endAt(item.getEndTime())
                .withDescription(item.getDescription());
        handleCalendar(triggerBuilder, item.getCalendarNames());
        return triggerBuilder;
    }

    public static Set<Trigger> buildCronTriggers(String tenantId, List<CronTriggerParam> paramOfTriggers) {
        Set<Trigger> quartzTriggers = new HashSet(paramOfTriggers.size());
        paramOfTriggers.forEach(
                item -> {
                    AbstractTrigger<CronTrigger> trigger = (AbstractTrigger<CronTrigger>) buildTrigger(tenantId, item).withSchedule(CronScheduleBuilder.cronSchedule(item.getExpression())
                            .inTimeZone(TimeZone.getTimeZone(item.getTimeZoneId()))).build();
                    trigger.setMisfireInstruction(item.getMisfireInstruction());
                    quartzTriggers.add(trigger);
                });
        return quartzTriggers;
    }

    public static Set<Trigger> buildCalendarOffsetTriggers(String tenantId, List<CalendarOffsetTriggerParam> paramOfTriggers) {
        Set<Trigger> quartzTriggers = new HashSet(paramOfTriggers.size());
        paramOfTriggers.forEach(
                item -> {
                    TimeOfDay startTimeOfDay = TimeUtil.toTime(item.getStartTimeOfDay());
                    CalendarOffsetScheduleBuilder scheduleBuilder = CalendarOffsetScheduleBuilder.calendarOffsetSchedule()
                            .withIntervalUnit(DateBuilder.IntervalUnit.valueOf(item.getTimeUnit()))
                            .withRepeatCount(item.getRepeatCount())
                            .withInnerOffset(item.getInnerOffset())
                            .withOuterOffset(item.getOuterOffset())
                            .reversed(item.isReversed());
                    if (startTimeOfDay != null) {
                        scheduleBuilder.startingDailyAt(startTimeOfDay);
                    }
                    AbstractTrigger<CalendarOffsetTrigger> trigger = (AbstractTrigger<CalendarOffsetTrigger>) buildTrigger(tenantId, item)
                            .withSchedule(scheduleBuilder).build();
                    trigger.setMisfireInstruction(item.getMisfireInstruction());
                    quartzTriggers.add(trigger);
                });
        return quartzTriggers;
    }

    public static Set<Trigger> buildCalendarIntervalTriggers(String tenantId, List<CalendarIntervalTriggerParam> paramOfTriggers) {
        Set<Trigger> quartzTriggers = new HashSet(paramOfTriggers.size());
        paramOfTriggers.forEach(
                item -> {
                    AbstractTrigger<CalendarIntervalTrigger> trigger = (AbstractTrigger<CalendarIntervalTrigger>) buildTrigger(tenantId, item).withSchedule(
                            CalendarIntervalScheduleBuilder.calendarIntervalSchedule()
                                    .inTimeZone(TimeZone.getTimeZone(item.getTimeZoneId()))
                                    .withRepeatCount(item.getRepeatCount())
                                    .preserveHourOfDayAcrossDaylightSavings(item.isPreserveDayLight())
                                    .skipDayIfHourDoesNotExist(item.isSkipDayIfNoHour())
                                    .withInterval(item.getInterval(), DateBuilder.IntervalUnit.valueOf(item.getTimeUnit())))
                            .build();
                    trigger.setMisfireInstruction(item.getMisfireInstruction());
                    quartzTriggers.add(trigger);
                });
        return quartzTriggers;
    }

    public static Set<Trigger> buildDailyIntervalTriggers(String tenantId, List<DailyTimeIntervalTriggerParam> paramOfTriggers) {
        Set<Trigger> quartzTriggers = new HashSet(paramOfTriggers.size());
        paramOfTriggers.forEach(
                item -> {
                    AbstractTrigger trigger;
                    if (Constants.TTYPE_SIMPLE.equals(item.getTriggerType())) {
                        SimpleScheduleBuilder builder = SimpleScheduleBuilder.simpleSchedule()
                                .withRepeatCount(item.getRepeatCount())
                                .withIntervalInMilliseconds(item.getInterval());
                        trigger = (AbstractTrigger<SimpleTrigger>) buildTrigger(tenantId, item).withSchedule(builder).build();
                    } else {
                        DailyTimeIntervalScheduleBuilder builder = DailyTimeIntervalScheduleBuilder.dailyTimeIntervalSchedule()
                                .withInterval(item.getInterval(), DateBuilder.IntervalUnit.valueOf(item.getTimeUnit()))
                                .endingDailyAt(TimeUtil.toTime(item.getEndTimeOfDay()))
                                .withRepeatCount(item.getRepeatCount());
                        TimeOfDay startTimeOfDay = TimeUtil.toTime(item.getStartTimeOfDay());
                        Set<Integer> days = TimeUtil.toDaysOfTheWeek(item.getDaysOfWeek());
                        if (startTimeOfDay != null) {
                            builder.startingDailyAt(startTimeOfDay);
                        }
                        if (days != null) {
                            builder.onDaysOfTheWeek(days);
                        }
                        trigger = (AbstractTrigger<DailyTimeIntervalTrigger>) buildTrigger(tenantId, item).withSchedule(builder).build();
                    }
                    trigger.setMisfireInstruction(item.getMisfireInstruction());
                    quartzTriggers.add(trigger);
                });
        return quartzTriggers;
    }

    private static void handleCalendar(TriggerBuilder<Trigger> triggerBuilder, List<String> calendarNames) {
        if (calendarNames == null || calendarNames.size() == 0) {
            triggerBuilder.modifiedByCalendar(null);
        } else if (calendarNames.size() == 1) {
            addThenGetQuartzCalendar(calendarNames.get(0));
            triggerBuilder.modifiedByCalendar(calendarNames.get(0));
        } else {
            BaseCalendar lastCalendar = addThenGetQuartzCalendar(calendarNames.get(0));
            BaseCalendar currentCalendar = null;
            for (int i = 1; i < calendarNames.size(); i++) {
                currentCalendar = addThenGetQuartzCalendar(calendarNames.get(i));
                currentCalendar.setBaseCalendar(lastCalendar);
                if (i == calendarNames.size() - 1) {
                    break;
                }
                lastCalendar = (BaseCalendar) currentCalendar.clone();
            }
            Scheduler scheduler = SpringContextAware.getBean(Scheduler.class);
            String combinedCalendarName = StringUtils.join(calendarNames, "-");
            if (addThenGetQuartzCalendar(combinedCalendarName) == null) {
                try {
                    scheduler.addCalendar(combinedCalendarName, currentCalendar, false, false);
                } catch (SchedulerException e) {
                    throw new RTException(CommonConstants.INTERNAL_SERVER_ERROR, e);
                }
            }
            triggerBuilder.modifiedByCalendar(combinedCalendarName);
        }
    }

    public static BaseCalendar addThenGetQuartzCalendar(String calendarName) {
        Scheduler scheduler = SpringContextAware.getBean(Scheduler.class);
        try {
            BaseCalendar baseCalendar = (BaseCalendar) scheduler.getCalendar(calendarName);
            if (baseCalendar == null) {
                CalendarService calendarService = SpringContextAware.getBean(CalendarService.class);
                CalendarInfo calendarInfo = calendarService.get(new CalendarGetParam(calendarName));
                if (calendarInfo == null) {
                    throw new RTException(3006, calendarName);
                }
                if (CalendarType.Day.getType() == calendarInfo.getType()) {
                    DayCalendarListParam listParam = new DayCalendarListParam();
                    listParam.setCalendarName(calendarName);
                    listParam.setPageSize(100000);
                    List<DayCalendarConfig> dayCalendarConfigs = calendarService.listDay(listParam);
                    HolidayCalendar holidayCalendar = new HolidayCalendar();
                    dayCalendarConfigs.forEach(item -> {
                        Integer dayNum = item.getDayNum();
                        if (dayNum == null) {
                            throw new RTException(6705);
                        }
                        Date date = DateTimeUtil.parseDateStr(String.valueOf(dayNum), CommonConstants.yyyyMMdd);
                        if (date == null) {
                            throw new RTException(6705);
                        } else {
                            holidayCalendar.addExcludedDate(date);
                        }
                    });
                    scheduler.addCalendar(calendarName, holidayCalendar, false, false);
                    baseCalendar = holidayCalendar;
                } else if (CalendarType.Time.getType() == calendarInfo.getType()) {
                    // TODO
                }
            }
            if (baseCalendar == null) {
                throw new RTException(3006, calendarName);
            }
            return baseCalendar;
        } catch (SchedulerException e) {
            throw new RTException(CommonConstants.INTERNAL_SERVER_ERROR, e);
        }
    }

    /**
     * add 5 second to current time
     *
     * @return
     */
    private static Date preHandleDate(Date date) {
        long mills = System.currentTimeMillis();
        if (date == null || date.getTime() < mills + 5 * 1000) {
            return new Date(mills + 5 * 1000);
        }
        return date;
    }
}