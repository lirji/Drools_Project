// 四眼职责分离（P1-8）的操作者身份。
//   dev/header 档：操作者 = X-Actor header（本 store 提供，控制台需人工填/选）——后端 changeStatus→上线时
//     校验审批人≠提交人。旧原生前端从未接线 X-Actor（勘察发现的缺口），本 store 补上。
//   auth 档：操作者 = JWT sub，后端自动取，前端不发 X-Actor（本 store 在 auth 档不参与 header 注入）。
import { defineStore } from 'pinia'
import { ref } from 'vue'

const ACTOR_KEY = 'actActor'

function readActor(): string {
  try {
    return localStorage.getItem(ACTOR_KEY) || ''
  } catch {
    return ''
  }
}

export const useActorStore = defineStore('actor', () => {
  const actor = ref(readActor())
  function setActor(a: string): void {
    actor.value = a || ''
    try {
      localStorage.setItem(ACTOR_KEY, actor.value)
    } catch {
      /* ignore */
    }
  }
  return { actor, setActor }
})
