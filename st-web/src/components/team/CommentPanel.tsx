import { useState, useEffect, useCallback } from 'react';
import { X, Send, Trash2, Pencil, CornerDownRight } from 'lucide-react';
import api from '../../lib/api';
import { useToast } from '../ui/Toast';
import { cn } from '../../lib/utils';
import type { TeamCommentItem, FileNode, UserSearch } from '../../types';

interface CommentPanelProps {
  spaceId: string;
  node: FileNode;
  onClose: () => void;
  canComment: boolean;
}

export default function CommentPanel({ spaceId, node, onClose, canComment }: CommentPanelProps) {
  const { showToast } = useToast();
  const [comments, setComments] = useState<TeamCommentItem[]>([]);
  const [content, setContent] = useState('');
  const [replyTo, setReplyTo] = useState<TeamCommentItem | null>(null);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [mentionUsers, setMentionUsers] = useState<UserSearch[]>([]);
  const [showMention, setShowMention] = useState(false);
  const [mentionedIds, setMentionedIds] = useState<string[]>([]);

  const fetchComments = useCallback(async () => {
    try { const res = await api.get<TeamCommentItem[]>(`/team/${spaceId}/comments/${node.id}`); setComments(res || []); } catch { /* ignore */ }
  }, [spaceId, node.id]);

  useEffect(() => { fetchComments(); }, [fetchComments]);

  const handleMentionSearch = (text: string, cursorPos: number) => {
    const beforeCursor = text.substring(0, cursorPos);
    const atMatch = beforeCursor.match(/@([^\s@]*)$/);
    if (atMatch) {
      setShowMention(true);
      if (atMatch[1].length >= 0) {
        api.get<UserSearch[]>(`/team/${spaceId}/users/search`, { params: { keyword: atMatch[1] } })
          .then(res => setMentionUsers(res || [])).catch(() => setMentionUsers([]));
      }
    } else {
      setShowMention(false);
    }
  };

  const handleSelectMention = (user: UserSearch) => {
    const newText = content.replace(/@([^\s@]*)$/, `@${user.nickname || user.username} `);
    setContent(newText);
    setMentionedIds(prev => [...new Set([...prev, user.userId])]);
    setShowMention(false);
  };

  const handleSend = async () => {
    if (!content.trim()) return;
    try {
      const mentions = mentionedIds.length > 0 ? mentionedIds.join(',') : undefined;
      if (editingId) {
        await api.put(`/team/${spaceId}/comments/${editingId}`, null, { params: { content } });
        setEditingId(null);
      } else {
        await api.post(`/team/${spaceId}/comments`, { nodeId: node.id, content, parentId: replyTo?.id, mentions });
      }
      setContent(''); setReplyTo(null); setMentionedIds([]);
      fetchComments();
      showToast('评论已发送', 'success');
    } catch { showToast('发送失败', 'error'); }
  };

  const handleDelete = async (commentId: string) => {
    if (!confirm('确定删除该评论？')) return;
    try { await api.delete(`/team/${spaceId}/comments/${commentId}`); fetchComments(); showToast('已删除', 'success'); } catch { showToast('删除失败', 'error'); }
  };

  const handleEdit = (c: TeamCommentItem) => { setEditingId(c.id); setContent(c.content); setReplyTo(null); };

  const startReply = (c: TeamCommentItem) => { setReplyTo(c); setContent(''); setEditingId(null); };

  const renderComment = (c: TeamCommentItem, isReply = false) => (
    <div key={c.id} className={cn('flex items-start gap-2.5', isReply && 'ml-8')}>
      <div className="w-7 h-7 bg-primary-600 rounded-full flex items-center justify-center text-white text-xs font-medium flex-shrink-0">{c.nickname?.[0] || c.username[0]}</div>
      <div className="min-w-0 flex-1">
        <div className="flex items-center gap-2">
          <span className="text-sm font-medium text-fg">{c.nickname || c.username}</span>
          <span className="text-xs text-muted">{new Date(c.createdAt).toLocaleString('zh-CN', { hour12: false })}</span>
        </div>
        <p className="text-sm text-fg mt-0.5 whitespace-pre-wrap">{c.content}</p>
        <div className="flex items-center gap-3 mt-1">
          <button onClick={() => startReply(c)} className="text-xs text-muted hover:text-primary-600 cursor-pointer flex items-center gap-0.5"><CornerDownRight className="w-3 h-3" />回复</button>
          <button onClick={() => handleEdit(c)} className="text-xs text-muted hover:text-primary-600 cursor-pointer flex items-center gap-0.5"><Pencil className="w-3 h-3" />编辑</button>
          <button onClick={() => handleDelete(c.id)} className="text-xs text-muted hover:text-red-500 cursor-pointer flex items-center gap-0.5"><Trash2 className="w-3 h-3" />删除</button>
        </div>
        {c.replies?.map(r => renderComment(r, true))}
      </div>
    </div>
  );

  return (
    <div className="fixed right-0 top-0 h-full w-80 bg-surface border-l border-border shadow-lg z-40 flex flex-col">
      <div className="flex items-center justify-between px-4 py-3 border-b border-border">
        <div className="min-w-0"><h3 className="text-sm font-semibold text-fg truncate">评论</h3><p className="text-xs text-muted truncate">{node.name}</p></div>
        <button onClick={onClose} className="text-muted hover:text-fg cursor-pointer" aria-label="关闭"><X className="w-5 h-5" /></button>
      </div>
      <div className="flex-1 overflow-auto p-4 space-y-4">
        {comments.length === 0 ? <div className="text-center py-8 text-sm text-muted">暂无评论</div> : comments.map(c => renderComment(c))}
      </div>
      {canComment && (
        <div className="border-t border-border p-3 relative">
          {replyTo && <div className="flex items-center gap-2 mb-1.5 text-xs text-muted"><span>回复 {replyTo.nickname || replyTo.username}</span><button onClick={() => setReplyTo(null)} className="text-muted hover:text-fg cursor-pointer"><X className="w-3 h-3" /></button></div>}
          {editingId && <div className="flex items-center gap-2 mb-1.5 text-xs text-muted"><span>编辑评论</span><button onClick={() => { setEditingId(null); setContent(''); }} className="text-muted hover:text-fg cursor-pointer"><X className="w-3 h-3" /></button></div>}
          {showMention && mentionUsers.length > 0 && (
            <div className="absolute bottom-full left-3 right-3 mb-1 bg-surface rounded-md border border-border shadow-lg max-h-40 overflow-auto z-10">
              {mentionUsers.map(u => (<button key={u.userId} onClick={() => handleSelectMention(u)} className="w-full flex items-center gap-2 px-3 py-2 text-sm hover:bg-surface-2 cursor-pointer text-left"><div className="w-6 h-6 bg-primary-600 rounded-full flex items-center justify-center text-white text-xs">{u.nickname?.[0] || u.username[0]}</div><span className="text-fg">{u.nickname || u.username}</span><span className="text-xs text-muted">@{u.username}</span></button>))}
            </div>
          )}
          <div className="flex gap-2">
            <textarea value={content} onChange={(e) => { setContent(e.target.value); handleMentionSearch(e.target.value, e.target.selectionStart); }} placeholder={replyTo ? `回复 ${replyTo.nickname || replyTo.username}...` : '发表评论... 输入@提及成员'} rows={2} className="flex-1 px-3 py-2 text-sm bg-surface-2 rounded-md border border-border outline-none focus:border-primary-400 focus:bg-surface transition-colors resize-none" />
            <button onClick={handleSend} disabled={!content.trim()} className="flex items-center justify-center w-9 h-9 text-white bg-primary-600 rounded-md hover:bg-primary-700 transition-colors cursor-pointer disabled:opacity-50 flex-shrink-0"><Send className="w-4 h-4" /></button>
          </div>
        </div>
      )}
    </div>
  );
}
