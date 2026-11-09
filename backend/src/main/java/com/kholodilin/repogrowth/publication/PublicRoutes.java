package com.kholodilin.repogrowth.publication;
import java.util.*;
public final class PublicRoutes {
 private PublicRoutes(){}
 public static final Set<String> RESERVED=Set.of("api","admin","login","logout","oauth2","settings","assets","actuator","dashboard","repositories","search-runs","error","v3","swagger-ui","favicon.ico","favicon.svg","logo.png","vite.svg","robots.txt","sitemap.xml");
 public static boolean owner(String name){return name!=null && name.matches("[A-Za-z0-9][A-Za-z0-9-]{0,99}")&&!RESERVED.contains(name.toLowerCase(Locale.ROOT))&&!name.toLowerCase(Locale.ROOT).startsWith("swagger-ui");}
 public static boolean repository(String name){return name!=null&&name.matches("[A-Za-z0-9_.-]{1,100}")&&!name.equals(".")&&!name.equals("..");}
}
