package com.kholodilin.repogrowth.credentials;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
@ConfigurationProperties("rgm.github-budget")
public record GitHubBudgetProperties(long coreHourly,long searchPerMinute,long coreReserve,long searchReserve,Duration permitLease) {
 public GitHubBudgetProperties {
  coreHourly=coreHourly==0?3000:coreHourly;searchPerMinute=searchPerMinute==0?20:searchPerMinute;
  coreReserve=coreReserve==0?10:coreReserve;searchReserve=searchReserve==0?1:searchReserve;
  permitLease=permitLease==null?Duration.ofSeconds(30):permitLease;
  if(coreHourly<=coreReserve || searchPerMinute<=searchReserve || coreReserve<0 || searchReserve<0 || permitLease.compareTo(Duration.ofSeconds(15))<0 || permitLease.compareTo(Duration.ofMinutes(2))>0)throw new IllegalArgumentException("Invalid GitHub budget policy");
 }
}
