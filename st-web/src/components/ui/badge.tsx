import * as React from "react"
import { cva, type VariantProps } from "class-variance-authority"
import { cn } from "@/lib/utils"

const badgeVariants = cva(
  "inline-flex items-center rounded-md border px-2 py-0.5 text-xs font-medium transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring",
  {
    variants: {
      variant: {
        default: "border-transparent bg-primary-600 text-white",
        secondary: "border-transparent bg-surface-2 text-fg",
        destructive: "border-transparent bg-red-500 text-white",
        outline: "text-fg border-border",
        blue: "border-transparent bg-blue-500/15 text-blue-500 dark:text-blue-400",
        red: "border-transparent bg-red-500/15 text-red-500 dark:text-red-400",
        green: "border-transparent bg-green-500/15 text-green-600 dark:text-green-400",
        amber: "border-transparent bg-amber-500/15 text-amber-600 dark:text-amber-400",
        purple: "border-transparent bg-purple-500/15 text-purple-500 dark:text-purple-400",
        cyan: "border-transparent bg-cyan-500/15 text-cyan-600 dark:text-cyan-400",
        gray: "border-transparent bg-muted/30 text-muted",
      },
    },
    defaultVariants: {
      variant: "default",
    },
  }
)

export interface BadgeProps
  extends React.HTMLAttributes<HTMLDivElement>,
    VariantProps<typeof badgeVariants> {}

function Badge({ className, variant, ...props }: BadgeProps) {
  return (
    <div className={cn(badgeVariants({ variant }), className)} {...props} />
  )
}

export { Badge, badgeVariants }