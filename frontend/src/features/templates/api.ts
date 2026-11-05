import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, unwrap, type Schemas } from '../../api/client';

export type Template = Schemas['Template'];
export type TemplateInput = Schemas['TemplateInput'];

export interface TemplateFilters {
  q?: string;
  channel_type?: string;
  status?: 'ACTIVE' | 'INACTIVE';
  page: number;
  size: number;
}

export function useTemplates(filters: TemplateFilters) {
  return useQuery({
    queryKey: ['templates', filters],
    queryFn: () =>
      unwrap(
        api.GET('/console/api/templates', {
          params: {
            query: {
              q: filters.q || undefined,
              channel_type: filters.channel_type || undefined,
              status: filters.status,
              page: filters.page,
              size: filters.size,
            },
          },
        }),
      ),
    placeholderData: keepPreviousData,
  });
}

export function useTemplate(code?: string) {
  return useQuery({
    queryKey: ['template', code],
    enabled: !!code,
    queryFn: () => unwrap(api.GET('/console/api/templates/{code}', { params: { path: { code: code! } } })),
  });
}

export function useSaveTemplate(code?: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (input: TemplateInput & { version?: number }) =>
      code
        ? unwrap(
            api.PUT('/console/api/templates/{code}', {
              params: { path: { code } },
              body: { ...input, version: input.version ?? 0 },
            }),
          )
        : unwrap(api.POST('/console/api/templates', { body: input })),
    onSuccess: (saved) => {
      qc.invalidateQueries({ queryKey: ['templates'] });
      qc.setQueryData(['template', saved.template_code], saved);
    },
  });
}

export function useSetTemplateActive(code: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ active, version }: { active: boolean; version: number }) =>
      active
        ? unwrap(
            api.POST('/console/api/templates/{code}/activate', { params: { path: { code }, query: { version } } }),
          )
        : unwrap(api.DELETE('/console/api/templates/{code}', { params: { path: { code }, query: { version } } })),
    onSuccess: (saved) => {
      qc.invalidateQueries({ queryKey: ['templates'] });
      qc.setQueryData(['template', code], saved);
    },
  });
}

export function usePreview() {
  return useMutation({
    mutationFn: (body: {
      channel_type: string;
      subject_template?: string;
      title_template?: string;
      body_template: string;
      variables: Record<string, string>;
    }) => unwrap(api.POST('/console/api/templates/preview', { body })),
  });
}
