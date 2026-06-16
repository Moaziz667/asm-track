import * as React from "react"

const MOBILE_BREAKPOINT = 768
const TABLET_BREAKPOINT = 1024

export function useIsMobile() {
  const [isMobile, setIsMobile] = React.useState<boolean | undefined>(undefined)

  React.useEffect(() => {
    const mql = window.matchMedia(`(max-width: ${MOBILE_BREAKPOINT - 1}px)`)
    const onChange = () => {
      setIsMobile(window.innerWidth < MOBILE_BREAKPOINT)
    }
    mql.addEventListener("change", onChange)
    setIsMobile(window.innerWidth < MOBILE_BREAKPOINT)
    return () => mql.removeEventListener("change", onChange)
  }, [])

  return !!isMobile
}

export function useBreakpoint() {
  const [breakpoint, setBreakpoint] = React.useState<{ isMobile: boolean; isTablet: boolean }>({
    isMobile: false,
    isTablet: false,
  })

  React.useEffect(() => {
    const mobileMql = window.matchMedia(`(max-width: ${MOBILE_BREAKPOINT - 1}px)`)
    const tabletMql = window.matchMedia(`(min-width: ${MOBILE_BREAKPOINT}px) and (max-width: ${TABLET_BREAKPOINT - 1}px)`)

    const onChange = () => {
      setBreakpoint({
        isMobile: window.innerWidth < MOBILE_BREAKPOINT,
        isTablet: window.innerWidth >= MOBILE_BREAKPOINT && window.innerWidth < TABLET_BREAKPOINT,
      })
    }

    mobileMql.addEventListener("change", onChange)
    tabletMql.addEventListener("change", onChange)
    
    onChange()

    return () => {
      mobileMql.removeEventListener("change", onChange)
      tabletMql.removeEventListener("change", onChange)
    }
  }, [])

  return breakpoint;
}

