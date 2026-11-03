import { useLayoutEffect, useRef } from "react";
import { useLocation, useNavigationType } from "react-router-dom";

/** Reset forward screen changes, but leave POP restoration and in-page controls alone. */
export function RouteScroll() {
  const location = useLocation();
  const navigation = useNavigationType();
  const previous = useRef(location.pathname);
  useLayoutEffect(() => {
    const changed = previous.current !== location.pathname;
    previous.current = location.pathname;
    if (changed && navigation !== "POP" && !location.hash && !new URLSearchParams(location.search).has("focus")) {
      window.scrollTo(0, 0);
    }
  }, [location, navigation]);
  return null;
}
