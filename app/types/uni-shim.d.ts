/// <reference types="vite/client" />
declare module '*.uvue' {
  import type { DefineComponent } from 'vue'
  const component: DefineComponent<object, object, unknown>
  export default component
}
