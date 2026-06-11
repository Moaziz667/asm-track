
import * as React from "react"
import { cn } from "@/lib/utils"

interface ProgressCircleProps extends React.SVGProps<SVGSVGElement> {
  value?: number // 0 to 100
  size?: number
  strokeWidth?: number
  showValue?: boolean
  label?: string
}

export function ProgressCircle({
  value = 0,
  size = 40,
  strokeWidth = 3,
  showValue = false,
  label,
  className,
  ...props
}: ProgressCircleProps) {
  const radius = (size - strokeWidth) / 2
  const circumference = radius * 2 * Math.PI
  const offset = circumference - (value / 100) * circumference

  return (
    <div className={cn("flex flex-col items-center justify-center gap-1", className)}>
      <div className="relative" style={{ width: size, height: size }}>
        <svg
          width={size}
          height={size}
          viewBox={`0 0 ${size} ${size}`}
          className="rotate-[-90deg]"
          {...props}
        >
          {/* Background circle */}
          <circle
            cx={size / 2}
            cy={size / 2}
            r={radius}
            fill="transparent"
            stroke="hsl(var(--secondary))"
            strokeWidth={strokeWidth}
          />
          {/* Progress circle */}
          <circle
            cx={size / 2}
            cy={size / 2}
            r={radius}
            fill="transparent"
            stroke="hsl(var(--primary))"
            strokeWidth={strokeWidth}
            strokeDasharray={circumference}
            style={{ strokeDashoffset: offset }}
            className="pro-progress-circle"
          />
        </svg>
        {showValue && (
          <div className="absolute inset-0 flex items-center justify-center text-2xs font-bold text-muted-foreground">
            {Math.round(value)}%
          </div>
        )}
      </div>
      {label && <span className="text-2xs font-semibold text-muted-foreground uppercase tracking-wider">{label}</span>}
    </div>
  )
}

