import * as React from "react"
import { cva, type VariantProps } from "class-variance-authority"
import { cn } from "@/lib/utils"

const badgeVariants = cva(
  "inline-flex items-center rounded-md border px-2 py-0.5 text-xs font-medium transition-colors focus:outline-none",
  {
    variants: {
      variant: {
        default: "border-transparent bg-primary-600 text-white",
        secondary: "border-transparent bg-stone-100 text-stone-900",
        destructive: "border-transparent bg-red-500 text-white",
        outline: "text-stone-950 border-stone-200",
        blue: "border-transparent bg-blue-50 text-blue-600",
        red: "border-transparent bg-red-50 text-red-600",
        green: "border-transparent bg-green-50 text-green-600",
        amber: "border-transparent bg-amber-50 text-amber-600",
        purple: "border-transparent bg-purple-50 text-purple-600",
        cyan: "border-transparent bg-cyan-50 text-cyan-600",
        gray: "border-transparent bg-stone-100 text-stone-600",
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
